-- Phase 6.3: Provider Payment Reconciliation Integration.
-- Creates tables for durable provider-payment reconciliation runs and results.
-- Matches internal payment_attempts against provider evidence from payment_webhook_events.
-- No ledger posting is performed; all collections remain ACCOUNTING_GATE_PENDING.

-- Add amount and currency to webhook inbox for fast, explicit reconciliation comparison
ALTER TABLE public.payment_webhook_events
    ADD COLUMN IF NOT EXISTS amount_minor BIGINT CHECK (amount_minor IS NULL OR amount_minor > 0),
    ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3) REFERENCES public.currencies(code);

-- Durable reconciliation runs table
CREATE TABLE public.provider_payment_reconciliation_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_number INTEGER NOT NULL UNIQUE CHECK (run_number > 0),
    rule_version VARCHAR(40) NOT NULL CHECK (length(trim(rule_version)) > 0),
    status VARCHAR(12) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    total_internal_records INTEGER NOT NULL DEFAULT 0 CHECK (total_internal_records >= 0),
    total_provider_records INTEGER NOT NULL DEFAULT 0 CHECK (total_provider_records >= 0),
    matched_count INTEGER NOT NULL DEFAULT 0 CHECK (matched_count >= 0),
    unmatched_internal_count INTEGER NOT NULL DEFAULT 0 CHECK (unmatched_internal_count >= 0),
    unmatched_provider_count INTEGER NOT NULL DEFAULT 0 CHECK (unmatched_provider_count >= 0),
    duplicate_count INTEGER NOT NULL DEFAULT 0 CHECK (duplicate_count >= 0),
    conflicting_count INTEGER NOT NULL DEFAULT 0 CHECK (conflicting_count >= 0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR
        (status IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)
    )
);

CREATE INDEX provider_payment_reconciliation_runs_started_idx
    ON public.provider_payment_reconciliation_runs(started_at DESC, id DESC);

-- Durable reconciliation results table
CREATE TABLE public.provider_payment_reconciliation_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES public.provider_payment_reconciliation_runs(id) ON DELETE CASCADE,
    attempt_id UUID REFERENCES public.payment_attempts(id),
    webhook_event_id UUID REFERENCES public.payment_webhook_events(id),
    provider_payment_id VARCHAR(80),
    provider_order_id VARCHAR(80),
    outcome VARCHAR(30) NOT NULL CHECK (
        outcome IN ('MATCHED', 'UNMATCHED_INTERNAL', 'UNMATCHED_PROVIDER', 'DUPLICATE', 'CONFLICTING')
    ),
    discrepancy_reason VARCHAR(240),
    details JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (pg_catalog.jsonb_typeof(details) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (attempt_id IS NOT NULL OR webhook_event_id IS NOT NULL)
);

CREATE INDEX provider_payment_reconciliation_results_run_outcome_idx
    ON public.provider_payment_reconciliation_results(run_id, outcome);
CREATE INDEX provider_payment_reconciliation_results_attempt_idx
    ON public.provider_payment_reconciliation_results(attempt_id)
    WHERE attempt_id IS NOT NULL;
CREATE INDEX provider_payment_reconciliation_results_webhook_idx
    ON public.provider_payment_reconciliation_results(webhook_event_id)
    WHERE webhook_event_id IS NOT NULL;

-- Append-only trigger for reconciliation results to prevent mutation
CREATE FUNCTION public.reject_provider_reconciliation_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    RAISE EXCEPTION 'Provider reconciliation records are immutable';
END;
$function$;

CREATE TRIGGER provider_reconciliation_results_immutable
BEFORE UPDATE OR DELETE ON public.provider_payment_reconciliation_results
FOR EACH ROW EXECUTE FUNCTION public.reject_provider_reconciliation_mutation();

REVOKE ALL ON FUNCTION public.reject_provider_reconciliation_mutation() FROM PUBLIC;

-- Runtime grants
GRANT SELECT, INSERT, UPDATE ON public.provider_payment_reconciliation_runs TO ledger_runtime;
GRANT SELECT, INSERT ON public.provider_payment_reconciliation_results TO ledger_runtime;
