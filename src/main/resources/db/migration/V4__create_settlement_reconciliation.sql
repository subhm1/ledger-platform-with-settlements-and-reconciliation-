-- Phase 4: settlement import and deterministic reconciliation.
-- V1, V2 and V3 remain unchanged.
-- All monetary values use integer minor units.
-- Reconciliation reads ledger data only; it never posts financial transactions.

CREATE TABLE public.settlement_batches (
    id UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    source_code VARCHAR(80) NOT NULL
        CHECK (length(trim(source_code)) > 0),
    report_id VARCHAR(150) NOT NULL
        CHECK (length(trim(report_id)) > 0),
    file_sha256 VARCHAR(64) NOT NULL
        CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    original_filename TEXT NOT NULL
        CHECK (length(trim(original_filename)) > 0),
    reporting_period_start TIMESTAMPTZ NOT NULL,
    reporting_period_end TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'IMPORTED'
        CHECK (status IN ('IMPORTED', 'RECONCILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (reporting_period_start < reporting_period_end),
    CONSTRAINT settlement_batches_source_report_uq
        UNIQUE (source_code, report_id),
    UNIQUE (id, source_code, report_id)
);

CREATE TABLE public.settlement_records (
    id UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES public.settlement_batches(id),
    row_number INTEGER NOT NULL CHECK (row_number > 0),
    raw_row_text TEXT NOT NULL,
    raw_row JSONB NOT NULL
        CHECK (pg_catalog.jsonb_typeof(raw_row) = 'object'),
    external_transaction_id TEXT,
    transaction_reference VARCHAR(150),
    currency_code TEXT,
    gross_amount_minor BIGINT,
    fee_amount_minor BIGINT,
    adjustment_amount_minor BIGINT,
    net_amount_minor BIGINT,
    validation_status VARCHAR(8) NOT NULL
        CHECK (validation_status IN ('VALID', 'INVALID')),
    validation_errors JSONB NOT NULL DEFAULT '[]'::JSONB
        CHECK (pg_catalog.jsonb_typeof(validation_errors) = 'array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT settlement_records_batch_row_uq UNIQUE (batch_id, row_number),
    UNIQUE (batch_id, id),
    CHECK (
        validation_status = 'INVALID'
        OR gross_amount_minor IS NULL
        OR gross_amount_minor >= 0
    ),
    CHECK (
        validation_status = 'INVALID'
        OR fee_amount_minor IS NULL
        OR fee_amount_minor >= 0
    ),
    CHECK (
        validation_status = 'INVALID'
        OR net_amount_minor IS NULL
        OR net_amount_minor >= 0
    ),
    CHECK (
        validation_status <> 'VALID'
        OR (
            external_transaction_id IS NOT NULL
            AND length(trim(external_transaction_id)) > 0
            AND transaction_reference IS NOT NULL
            AND length(trim(transaction_reference)) > 0
            AND currency_code IS NOT NULL
            AND currency_code ~ '^[A-Z]{3}$'
            AND gross_amount_minor IS NOT NULL
            AND fee_amount_minor IS NOT NULL
            AND adjustment_amount_minor IS NOT NULL
            AND net_amount_minor IS NOT NULL
            AND net_amount_minor::NUMERIC =
                gross_amount_minor::NUMERIC
                - fee_amount_minor::NUMERIC
                + adjustment_amount_minor::NUMERIC
            AND validation_errors = '[]'::JSONB
        )
    ),
    CHECK (
        validation_status <> 'INVALID'
        OR pg_catalog.jsonb_array_length(validation_errors) > 0
    )
);

CREATE INDEX settlement_records_reference_idx
    ON public.settlement_records(batch_id, transaction_reference);
CREATE INDEX settlement_records_external_id_idx
    ON public.settlement_records(batch_id, external_transaction_id);

CREATE TABLE public.reconciliation_runs (
    id UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES public.settlement_batches(id),
    run_number INTEGER NOT NULL CHECK (run_number > 0),
    rule_version VARCHAR(40) NOT NULL CHECK (length(trim(rule_version)) > 0),
    status VARCHAR(12) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT reconciliation_runs_batch_number_uq
        UNIQUE (batch_id, run_number),
    UNIQUE (batch_id, id),
    CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR
        (status IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)
    )
);

CREATE TABLE public.reconciliation_results (
    id UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    batch_id UUID NOT NULL,
    run_id UUID NOT NULL,
    external_record_id UUID,
    internal_transaction_id UUID,
    outcome VARCHAR(24) NOT NULL CHECK (
        outcome IN (
            'MATCHED', 'EXTERNAL_ONLY', 'INTERNAL_ONLY',
            'DUPLICATE_EXTERNAL', 'AMOUNT_MISMATCH',
            'AMBIGUOUS', 'INVALID'
        )
    ),
    details JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (pg_catalog.jsonb_typeof(details) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (batch_id, run_id)
        REFERENCES public.reconciliation_runs(batch_id, id),
    FOREIGN KEY (batch_id, external_record_id)
        REFERENCES public.settlement_records(batch_id, id),
    FOREIGN KEY (internal_transaction_id)
        REFERENCES public.journal_transactions(id),
    UNIQUE (run_id, external_record_id),
    UNIQUE (run_id, internal_transaction_id),
    CHECK (external_record_id IS NOT NULL OR internal_transaction_id IS NOT NULL)
);

CREATE INDEX reconciliation_results_run_outcome_idx
    ON public.reconciliation_results(run_id, outcome);
CREATE INDEX reconciliation_results_internal_tx_idx
    ON public.reconciliation_results(internal_transaction_id);

CREATE TABLE public.reconciliation_resolution_audits (
    id UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    result_id UUID NOT NULL REFERENCES public.reconciliation_results(id),
    action VARCHAR(30) NOT NULL CHECK (
        action IN ('CONFIRM_MATCH', 'MARK_EXPECTED', 'REJECT_EXTERNAL', 'ANNOTATE')
    ),
    actor_reference VARCHAR(150) NOT NULL
        CHECK (length(trim(actor_reference)) > 0),
    reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
    resolution_details JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (pg_catalog.jsonb_typeof(resolution_details) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX reconciliation_resolution_result_idx
    ON public.reconciliation_resolution_audits(result_id, created_at);

-- Parsed rows marked INVALID are preserved with validation errors.
-- Invalid JSON payloads or constraint violations roll back the batch.
-- Identical report identity and hash returns the existing batch.

CREATE FUNCTION public.import_settlement_batch(
    p_source_code VARCHAR(80),
    p_report_id VARCHAR(150),
    p_file_sha256 VARCHAR(64),
    p_original_filename TEXT,
    p_reporting_period_start TIMESTAMPTZ,
    p_reporting_period_end TIMESTAMPTZ,
    p_rows JSONB
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    v_batch_id UUID;
    v_existing_hash VARCHAR(64);
    v_item JSONB;
BEGIN
    IF p_source_code IS NULL OR length(trim(p_source_code)) = 0
       OR length(trim(p_source_code)) > 80
       OR p_report_id IS NULL OR length(trim(p_report_id)) = 0
       OR length(trim(p_report_id)) > 150
       OR p_file_sha256 IS NULL OR p_file_sha256 !~ '^[0-9a-f]{64}$'
       OR p_original_filename IS NULL OR length(trim(p_original_filename)) = 0
       OR length(trim(p_original_filename)) > 255
       OR p_reporting_period_start IS NULL OR p_reporting_period_end IS NULL
       OR p_reporting_period_start >= p_reporting_period_end THEN
        RAISE EXCEPTION 'Invalid settlement batch metadata';
    END IF;

    IF p_rows IS NULL
       OR pg_catalog.jsonb_typeof(p_rows) <> 'array'
       OR pg_catalog.jsonb_array_length(p_rows) = 0 THEN
        RAISE EXCEPTION 'Settlement report must contain rows';
    END IF;

    INSERT INTO public.settlement_batches (
        source_code, report_id, file_sha256, original_filename,
        reporting_period_start, reporting_period_end
    )
    VALUES (
        trim(p_source_code), trim(p_report_id), p_file_sha256,
        trim(p_original_filename), p_reporting_period_start, p_reporting_period_end
    )
    ON CONFLICT (source_code, report_id) DO NOTHING
    RETURNING id INTO v_batch_id;

    IF v_batch_id IS NULL THEN
        SELECT b.id, b.file_sha256
          INTO v_batch_id, v_existing_hash
          FROM public.settlement_batches b
         WHERE b.source_code = trim(p_source_code)
           AND b.report_id = trim(p_report_id)
         FOR UPDATE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Settlement batch identity could not be resolved';
        END IF;

        IF v_existing_hash <> p_file_sha256 THEN
            RAISE EXCEPTION 'Report identity already exists with different content';
        END IF;

        RETURN v_batch_id;
    END IF;

    FOR v_item IN
        SELECT element
          FROM pg_catalog.jsonb_array_elements(p_rows) AS rows(element)
    LOOP
        IF pg_catalog.jsonb_typeof(v_item) <> 'object' THEN
            RAISE EXCEPTION 'Each settlement row must be an object';
        END IF;

        IF pg_catalog.jsonb_typeof(v_item -> 'row_number') <> 'number'
           OR pg_catalog.jsonb_typeof(v_item -> 'raw_row') <> 'object'
           OR pg_catalog.jsonb_typeof(v_item -> 'validation_errors') <> 'array'
           OR v_item ->> 'validation_status' NOT IN ('VALID', 'INVALID') THEN
            RAISE EXCEPTION 'Settlement row has an invalid structure';
        END IF;

        INSERT INTO public.settlement_records (
            batch_id, row_number, raw_row_text, raw_row,
            external_transaction_id, transaction_reference, currency_code,
            gross_amount_minor, fee_amount_minor, adjustment_amount_minor,
            net_amount_minor, validation_status, validation_errors
        )
        VALUES (
            v_batch_id,
            (v_item ->> 'row_number')::INTEGER,
            v_item ->> 'raw_row_text',
            v_item -> 'raw_row',
            NULLIF(v_item ->> 'external_transaction_id', ''),
            NULLIF(v_item ->> 'transaction_reference', ''),
            NULLIF(v_item ->> 'currency_code', ''),
            NULLIF(v_item ->> 'gross_amount_minor', '')::BIGINT,
            NULLIF(v_item ->> 'fee_amount_minor', '')::BIGINT,
            NULLIF(v_item ->> 'adjustment_amount_minor', '')::BIGINT,
            NULLIF(v_item ->> 'net_amount_minor', '')::BIGINT,
            v_item ->> 'validation_status',
            COALESCE(v_item -> 'validation_errors', '[]'::JSONB)
        );
    END LOOP;

    RETURN v_batch_id;
END;
$function$;


-- Reconcile using exact transaction reference + currency.
-- Gross amount is compared with total debit minor units in the internal transaction.
-- Duplicate external IDs or references are not auto-matched.
-- Internal-only detection is scoped to the batch period and currencies present
-- among valid report rows.
CREATE FUNCTION public.reconcile_settlement_batch(
    p_batch_id UUID,
    p_rule_version VARCHAR(40)
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    v_batch public.settlement_batches%ROWTYPE;
    v_run_id UUID;
    v_run_number INTEGER;
BEGIN
    IF p_batch_id IS NULL
       OR p_rule_version IS NULL
       OR length(trim(p_rule_version)) = 0 THEN
        RAISE EXCEPTION 'Batch ID and rule version are required';
    END IF;

    SELECT *
      INTO v_batch
      FROM public.settlement_batches
     WHERE id = p_batch_id
     FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Settlement batch was not found';
    END IF;

    SELECT COALESCE(MAX(run_number), 0) + 1
      INTO v_run_number
      FROM public.reconciliation_runs
     WHERE batch_id = p_batch_id;

    INSERT INTO public.reconciliation_runs (
        batch_id, run_number, rule_version, status
    )
    VALUES (p_batch_id, v_run_number, trim(p_rule_version), 'RUNNING')
    RETURNING id INTO v_run_id;

    -- Every imported row receives one classification.
    INSERT INTO public.reconciliation_results (
        batch_id, run_id, external_record_id, internal_transaction_id,
        outcome, details
    )
    SELECT
        p_batch_id,
        v_run_id,
        s.id,
        CASE
            WHEN s.validation_status = 'VALID'
             AND dup.duplicate_count = 1
             AND candidate.transaction_id IS NOT NULL
             AND candidate.candidate_count = 1
            THEN candidate.transaction_id
            ELSE NULL
        END,
        CASE
            WHEN s.validation_status = 'INVALID' THEN 'INVALID'
            WHEN dup.duplicate_count > 1 THEN 'DUPLICATE_EXTERNAL'
            WHEN candidate.candidate_count > 1 THEN 'AMBIGUOUS'
            WHEN candidate.transaction_id IS NULL THEN 'EXTERNAL_ONLY'
            WHEN candidate.debit_total <> s.gross_amount_minor THEN 'AMOUNT_MISMATCH'
            ELSE 'MATCHED'
        END,
        pg_catalog.jsonb_build_object(
            'row_number', s.row_number,
            'transaction_reference', s.transaction_reference,
            'currency_code', s.currency_code,
            'reported_gross_minor', s.gross_amount_minor,
            'internal_debit_total_minor', candidate.debit_total,
            'rule_version', trim(p_rule_version),
            'validation_errors', s.validation_errors
        )
    FROM public.settlement_records s
    CROSS JOIN LATERAL (
        SELECT
            CASE WHEN
                (SELECT COUNT(*)
                   FROM public.settlement_records sx
                  WHERE sx.batch_id = s.batch_id
                    AND sx.validation_status = 'VALID'
                    AND (
                        sx.external_transaction_id = s.external_transaction_id
                        OR sx.transaction_reference = s.transaction_reference
                    )
                ) > 1
            THEN 2 ELSE 1 END AS duplicate_count
    ) dup
    LEFT JOIN LATERAL (
        SELECT
            jt.id AS transaction_id,
            COUNT(*) OVER () AS candidate_count,
            SUM(je.amount_minor) FILTER (WHERE je.direction = 'DEBIT') AS debit_total
        FROM public.journal_transactions jt
        JOIN public.journal_entries je ON je.transaction_id = jt.id
        WHERE s.validation_status = 'VALID'
          AND dup.duplicate_count = 1
          AND jt.transaction_reference = s.transaction_reference
          AND jt.currency_code = s.currency_code
          AND jt.posted_at >= v_batch.reporting_period_start
          AND jt.posted_at < v_batch.reporting_period_end
        GROUP BY jt.id
    ) candidate ON TRUE
    WHERE s.batch_id = p_batch_id;

    -- Identify eligible internal transactions absent from the report.
    INSERT INTO public.reconciliation_results (
        batch_id, run_id, external_record_id, internal_transaction_id,
        outcome, details
    )
    SELECT
        p_batch_id,
        v_run_id,
        NULL,
        jt.id,
        'INTERNAL_ONLY',
        pg_catalog.jsonb_build_object(
            'transaction_reference', jt.transaction_reference,
            'currency_code', jt.currency_code,
            'posted_at', jt.posted_at,
            'rule_version', trim(p_rule_version)
        )
    FROM public.journal_transactions jt
    WHERE jt.posted_at >= v_batch.reporting_period_start
      AND jt.posted_at < v_batch.reporting_period_end
      AND EXISTS (
          SELECT 1
            FROM public.settlement_records sr
           WHERE sr.batch_id = p_batch_id
             AND sr.validation_status = 'VALID'
             AND sr.currency_code = jt.currency_code
      )
      AND NOT EXISTS (
          SELECT 1
            FROM public.settlement_records sr
           WHERE sr.batch_id = p_batch_id
             AND sr.validation_status = 'VALID'
             AND sr.transaction_reference = jt.transaction_reference
             AND sr.currency_code = jt.currency_code
      );

    UPDATE public.reconciliation_runs
       SET status = 'COMPLETED',
           completed_at = CURRENT_TIMESTAMP
     WHERE id = v_run_id;

    UPDATE public.settlement_batches
       SET status = 'RECONCILED'
     WHERE id = p_batch_id;

    RETURN v_run_id;
END;
$function$;


-- Runtime can read reconciliation data but cannot mutate tables directly.
GRANT SELECT ON
    public.settlement_batches,
    public.settlement_records,
    public.reconciliation_runs,
    public.reconciliation_results,
    public.reconciliation_resolution_audits
TO ledger_executor;

GRANT UPDATE (status) ON public.settlement_batches TO ledger_executor;
GRANT UPDATE (status, completed_at)
    ON public.reconciliation_runs TO ledger_executor;

GRANT SELECT ON
    public.settlement_batches,
    public.settlement_records,
    public.reconciliation_runs,
    public.reconciliation_results,
    public.reconciliation_resolution_audits
TO ledger_runtime;

-- SECURITY DEFINER functions execute as ledger_executor. Grant only the writes
-- those functions perform; do not grant direct access to the resolution audit.
GRANT INSERT ON public.settlement_batches, public.settlement_records,
    public.reconciliation_runs, public.reconciliation_results
TO ledger_executor;
GRANT UPDATE (status) ON public.settlement_batches TO ledger_executor;
GRANT UPDATE (status, completed_at)
    ON public.reconciliation_runs TO ledger_executor;

GRANT SELECT ON
    public.journal_transactions,
    public.journal_entries
TO ledger_executor;

-- PostgreSQL requires CREATE on the containing schema to transfer function
-- ownership to another role. Keep the grant limited to this migration step.
GRANT CREATE ON SCHEMA public TO ledger_executor;

ALTER FUNCTION public.import_settlement_batch(
    VARCHAR, VARCHAR, VARCHAR, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, JSONB
) OWNER TO ledger_executor;

ALTER FUNCTION public.reconcile_settlement_batch(UUID, VARCHAR)
    OWNER TO ledger_executor;

REVOKE CREATE ON SCHEMA public FROM ledger_executor;

REVOKE ALL ON FUNCTION public.import_settlement_batch(
    VARCHAR, VARCHAR, VARCHAR, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, JSONB
) FROM PUBLIC;

REVOKE ALL ON FUNCTION public.reconcile_settlement_batch(UUID, VARCHAR)
    FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.import_settlement_batch(
    VARCHAR, VARCHAR, VARCHAR, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, JSONB
) TO ledger_runtime;

GRANT EXECUTE ON FUNCTION public.reconcile_settlement_batch(UUID, VARCHAR)
    TO ledger_runtime;
