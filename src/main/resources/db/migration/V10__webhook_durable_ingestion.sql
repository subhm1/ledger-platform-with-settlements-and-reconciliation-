-- Phase 6.3: Durable webhook ingestion.
-- Adds safe_payload_json for auditable raw payload storage on payment_webhook_events.
-- Adds webhook_event_id FK column on payment_attempt_events for traceability.
-- Applied migrations V1–V9 are not modified.

-- Store a sanitised (non-secret) snapshot of the raw JSON payload for audit.
-- This column is append-only in practice (set on INSERT; trigger prevents UPDATE).
ALTER TABLE public.payment_webhook_events
    ADD COLUMN safe_payload_json JSONB;

-- Traceability: record which webhook event caused a payment attempt state change.
-- Nullable because most events originate from the Checkout path, not webhooks.
ALTER TABLE public.payment_attempt_events
    ADD COLUMN webhook_event_id UUID REFERENCES public.payment_webhook_events(id);

CREATE INDEX payment_attempt_events_webhook_idx
    ON public.payment_attempt_events(webhook_event_id)
    WHERE webhook_event_id IS NOT NULL;

-- ledger_runtime already has INSERT on payment_webhook_events (V5).
-- No new grants are required because ALTER TABLE ADD COLUMN inherits existing grants.

-- Confirm: no journal_transactions or journal_entries rows are touched by this migration.
