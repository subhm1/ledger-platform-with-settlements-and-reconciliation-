-- Provider Payments event history. Forward-only; do not edit applied V1-V7.
CREATE TABLE public.payment_attempt_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id UUID NOT NULL REFERENCES public.payment_attempts(id),
    event_type VARCHAR(40) NOT NULL CHECK (event_type ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    previous_state VARCHAR(32),
    new_state VARCHAR(32) NOT NULL CHECK (new_state IN (
        'ORDER_PENDING', 'ORDER_UNKNOWN', 'READY_FOR_CHECKOUT', 'PAYMENT_PENDING',
        'AUTHORIZED', 'CAPTURED_VERIFIED', 'FAILED', 'REVIEW_REQUIRED'
    )),
    safe_detail VARCHAR(240) NOT NULL DEFAULT '',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX payment_attempt_events_attempt_time_idx
    ON public.payment_attempt_events(attempt_id, occurred_at, id);

CREATE FUNCTION public.reject_payment_attempt_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    RAISE EXCEPTION 'Payment attempt events are append-only';
END;
$function$;
CREATE TRIGGER payment_attempt_events_append_only
BEFORE UPDATE OR DELETE ON public.payment_attempt_events
FOR EACH ROW EXECUTE FUNCTION public.reject_payment_attempt_event_mutation();
REVOKE ALL ON FUNCTION public.reject_payment_attempt_event_mutation() FROM PUBLIC;
GRANT SELECT, INSERT ON public.payment_attempt_events TO ledger_runtime;
