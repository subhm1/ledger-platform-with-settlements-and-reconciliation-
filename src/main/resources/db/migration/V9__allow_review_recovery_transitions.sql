-- Allow verified provider evidence to move an attempt out of REVIEW_REQUIRED.
-- Captured attempts still cannot recede except into REVIEW_REQUIRED.
CREATE OR REPLACE FUNCTION public.guard_payment_attempt_lifecycle()
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
        OR (OLD.state = 'ORDER_UNKNOWN' AND NEW.state = 'READY_FOR_CHECKOUT'
            AND NEW.provider_order_id IS NOT NULL)
        OR (OLD.state = 'READY_FOR_CHECKOUT' AND NEW.state IN
            ('PAYMENT_PENDING','AUTHORIZED','CAPTURED_VERIFIED','FAILED','REVIEW_REQUIRED'))
        OR (OLD.state = 'PAYMENT_PENDING' AND NEW.state IN
            ('AUTHORIZED','CAPTURED_VERIFIED','FAILED','REVIEW_REQUIRED'))
        OR (OLD.state = 'AUTHORIZED' AND NEW.state IN
            ('PAYMENT_PENDING','CAPTURED_VERIFIED','FAILED','REVIEW_REQUIRED'))
        OR (OLD.state = 'FAILED' AND NEW.state IN
            ('PAYMENT_PENDING','AUTHORIZED','CAPTURED_VERIFIED','REVIEW_REQUIRED'))
        OR (OLD.state = 'CAPTURED_VERIFIED' AND NEW.state = 'REVIEW_REQUIRED')
        OR (OLD.state = 'REVIEW_REQUIRED' AND NEW.state IN
            ('PAYMENT_PENDING','AUTHORIZED','CAPTURED_VERIFIED','FAILED','READY_FOR_CHECKOUT')
            AND (NEW.state <> 'READY_FOR_CHECKOUT' OR NEW.provider_order_id IS NOT NULL))
    ) THEN
        RAISE EXCEPTION 'Invalid payment attempt state transition';
    END IF;

    RETURN NEW;
END;
$function$;
