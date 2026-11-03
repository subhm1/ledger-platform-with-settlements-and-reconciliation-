-- Phase 6.2 Part 2: durable Razorpay Test Mode payment collection evidence.
-- No journal posting is defined here; collection accounting remains gated.

CREATE TABLE public.payment_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_scope VARCHAR(120) NOT NULL CHECK (length(trim(idempotency_scope)) > 0),
    idempotency_key VARCHAR(180) NOT NULL CHECK (length(trim(idempotency_key)) > 0),
    request_fingerprint VARCHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt VARCHAR(40) NOT NULL UNIQUE CHECK (length(trim(receipt)) > 0),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency_code VARCHAR(3) NOT NULL REFERENCES public.currencies(code),
    purpose VARCHAR(100) NOT NULL CHECK (length(trim(purpose)) BETWEEN 1 AND 100),
    business_reference VARCHAR(150) NOT NULL CHECK (length(trim(business_reference)) BETWEEN 1 AND 150),
    state VARCHAR(32) NOT NULL CHECK (state IN (
        'ORDER_PENDING', 'ORDER_UNKNOWN', 'READY_FOR_CHECKOUT', 'PAYMENT_PENDING',
        'AUTHORIZED', 'CAPTURED_VERIFIED', 'FAILED', 'REVIEW_REQUIRED'
    )),
    provider VARCHAR(20) NOT NULL DEFAULT 'RAZORPAY' CHECK (provider = 'RAZORPAY'),
    environment VARCHAR(10) NOT NULL DEFAULT 'TEST' CHECK (environment = 'TEST'),
    provider_order_id VARCHAR(80) UNIQUE,
    provider_payment_id VARCHAR(80) UNIQUE,
    safe_error_code VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (idempotency_scope, idempotency_key),
    CHECK ((state = 'READY_FOR_CHECKOUT' AND provider_order_id IS NOT NULL)
        OR state <> 'READY_FOR_CHECKOUT'),
    CHECK (safe_error_code IS NULL OR length(safe_error_code) <= 80)
);

CREATE INDEX payment_attempts_created_idx ON public.payment_attempts(created_at DESC);
CREATE INDEX payment_attempts_state_idx ON public.payment_attempts(state, updated_at);

CREATE TABLE public.payment_webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(20) NOT NULL DEFAULT 'RAZORPAY' CHECK (provider = 'RAZORPAY'),
    environment VARCHAR(10) NOT NULL DEFAULT 'TEST' CHECK (environment = 'TEST'),
    provider_event_id VARCHAR(150) NOT NULL CHECK (length(trim(provider_event_id)) > 0),
    event_type VARCHAR(100) NOT NULL CHECK (length(trim(event_type)) > 0),
    provider_order_id VARCHAR(80),
    provider_payment_id VARCHAR(80),
    signature_verified BOOLEAN NOT NULL CHECK (signature_verified),
    processing_state VARCHAR(20) NOT NULL DEFAULT 'RECEIVED'
        CHECK (processing_state IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'RETRY_WAIT', 'REVIEW_REQUIRED')),
    attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 20),
    next_attempt_at TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    safe_error_code VARCHAR(80),
    UNIQUE (provider, environment, provider_event_id),
    CHECK (safe_error_code IS NULL OR length(safe_error_code) <= 80)
);

CREATE INDEX payment_webhook_events_pending_idx
    ON public.payment_webhook_events(processing_state, next_attempt_at, received_at);
CREATE INDEX payment_webhook_events_order_idx ON public.payment_webhook_events(provider_order_id);
CREATE INDEX payment_webhook_events_payment_idx ON public.payment_webhook_events(provider_payment_id);

CREATE FUNCTION public.guard_payment_attempt_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'ORDER_PENDING' OR NEW.provider_order_id IS NOT NULL
           OR NEW.provider_payment_id IS NOT NULL OR NEW.version <> 0 THEN
            RAISE EXCEPTION 'Payment attempts must start in ORDER_PENDING';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.idempotency_scope IS DISTINCT FROM OLD.idempotency_scope
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
       OR NEW.receipt IS DISTINCT FROM OLD.receipt
       OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
       OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
       OR NEW.purpose IS DISTINCT FROM OLD.purpose
       OR NEW.business_reference IS DISTINCT FROM OLD.business_reference
       OR NEW.provider IS DISTINCT FROM OLD.provider
       OR NEW.environment IS DISTINCT FROM OLD.environment THEN
        RAISE EXCEPTION 'Payment attempt identity is immutable';
    END IF;

    IF OLD.provider_order_id IS NOT NULL
       AND NEW.provider_order_id IS DISTINCT FROM OLD.provider_order_id THEN
        RAISE EXCEPTION 'Provider order identity is immutable';
    END IF;
    IF OLD.provider_payment_id IS NOT NULL
       AND NEW.provider_payment_id IS DISTINCT FROM OLD.provider_payment_id THEN
        RAISE EXCEPTION 'Provider payment identity is immutable';
    END IF;

    IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
        (OLD.state = 'ORDER_PENDING' AND NEW.state IN ('READY_FOR_CHECKOUT','ORDER_UNKNOWN','REVIEW_REQUIRED'))
        OR (OLD.state = 'ORDER_UNKNOWN' AND NEW.state = 'REVIEW_REQUIRED')
        OR (OLD.state = 'READY_FOR_CHECKOUT' AND NEW.state IN
            ('PAYMENT_PENDING','AUTHORIZED','CAPTURED_VERIFIED','FAILED','REVIEW_REQUIRED'))
        OR (OLD.state = 'PAYMENT_PENDING' AND NEW.state IN
            ('AUTHORIZED','CAPTURED_VERIFIED','FAILED','REVIEW_REQUIRED'))
        OR (OLD.state = 'AUTHORIZED' AND NEW.state IN
            ('PAYMENT_PENDING','CAPTURED_VERIFIED','FAILED','REVIEW_REQUIRED'))
        OR (OLD.state = 'FAILED' AND NEW.state IN
            ('PAYMENT_PENDING','AUTHORIZED','CAPTURED_VERIFIED','REVIEW_REQUIRED'))
        OR (OLD.state = 'CAPTURED_VERIFIED' AND NEW.state = 'REVIEW_REQUIRED')
    ) THEN
        RAISE EXCEPTION 'Invalid payment attempt state transition';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE TRIGGER payment_attempt_lifecycle_guard
BEFORE INSERT OR UPDATE ON public.payment_attempts
FOR EACH ROW EXECUTE FUNCTION public.guard_payment_attempt_lifecycle();

CREATE FUNCTION public.guard_payment_webhook_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.processing_state <> 'RECEIVED' OR NEW.attempt_count <> 0
           OR NEW.processed_at IS NOT NULL OR NEW.signature_verified IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'Webhook events must start as verified RECEIVED events';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.provider IS DISTINCT FROM OLD.provider
       OR NEW.environment IS DISTINCT FROM OLD.environment
       OR NEW.provider_event_id IS DISTINCT FROM OLD.provider_event_id
       OR NEW.event_type IS DISTINCT FROM OLD.event_type
       OR NEW.provider_order_id IS DISTINCT FROM OLD.provider_order_id
       OR NEW.provider_payment_id IS DISTINCT FROM OLD.provider_payment_id
       OR NEW.signature_verified IS DISTINCT FROM OLD.signature_verified
       OR NEW.received_at IS DISTINCT FROM OLD.received_at THEN
        RAISE EXCEPTION 'Webhook event identity and verification are immutable';
    END IF;

    IF NEW.processing_state IS DISTINCT FROM OLD.processing_state AND NOT (
        (OLD.processing_state = 'RECEIVED' AND NEW.processing_state IN ('PROCESSING','REVIEW_REQUIRED'))
        OR (OLD.processing_state = 'RETRY_WAIT' AND NEW.processing_state IN ('PROCESSING','REVIEW_REQUIRED'))
        OR (OLD.processing_state = 'PROCESSING' AND NEW.processing_state IN
            ('PROCESSED','RETRY_WAIT','REVIEW_REQUIRED'))
    ) THEN
        RAISE EXCEPTION 'Invalid webhook processing transition';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count OR NEW.attempt_count > OLD.attempt_count + 1 THEN
        RAISE EXCEPTION 'Webhook attempt count must advance by at most one';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER payment_webhook_lifecycle_guard
BEFORE INSERT OR UPDATE ON public.payment_webhook_events
FOR EACH ROW EXECUTE FUNCTION public.guard_payment_webhook_lifecycle();

REVOKE ALL ON FUNCTION public.guard_payment_attempt_lifecycle() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_payment_webhook_lifecycle() FROM PUBLIC;

-- The app may persist lifecycle metadata, but posted ledger tables remain protected by V2.
GRANT SELECT, INSERT ON public.payment_attempts TO ledger_runtime;
GRANT UPDATE (state, provider_order_id, provider_payment_id, safe_error_code, version, updated_at)
    ON public.payment_attempts TO ledger_runtime;
GRANT SELECT, INSERT ON public.payment_webhook_events TO ledger_runtime;
GRANT UPDATE (processing_state, attempt_count, next_attempt_at, processing_started_at, processed_at, safe_error_code)
    ON public.payment_webhook_events TO ledger_runtime;
