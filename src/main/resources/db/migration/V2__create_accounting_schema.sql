-- Phase 1: Accounting schema and controlled posting.
-- V1 is immutable. Apply only after review and isolated tests.

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

CREATE TABLE public.currencies (
    code VARCHAR(3) PRIMARY KEY
        CHECK (code ~ '^[A-Z]{3}$'),
    minor_unit_exponent SMALLINT NOT NULL
        CHECK (minor_unit_exponent BETWEEN 0 AND 6),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE public.accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_code VARCHAR(100) NOT NULL UNIQUE,
    account_class VARCHAR(10) NOT NULL
        CHECK (account_class IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    account_role VARCHAR(30) NOT NULL
        CHECK (account_role IN (
            'CUSTOMER_WALLET','MERCHANT_PAYABLE','SETTLEMENT_CASH',
            'BANK_CLEARING','FEE_REVENUE','PLATFORM_EXPENSE','SUSPENSE'
        )),
    currency_code VARCHAR(3) NOT NULL REFERENCES public.currencies(code),
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, currency_code)
);

CREATE TABLE public.journal_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_reference VARCHAR(150) NOT NULL UNIQUE,
    currency_code VARCHAR(3) NOT NULL REFERENCES public.currencies(code),
    description TEXT NOT NULL CHECK (length(trim(description)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reversal_of_transaction_id UUID UNIQUE
        REFERENCES public.journal_transactions(id),
    UNIQUE (id, currency_code),
    CHECK (reversal_of_transaction_id IS NULL OR reversal_of_transaction_id <> id)
);

CREATE TABLE public.journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (transaction_id, currency_code)
        REFERENCES public.journal_transactions(id, currency_code),
    FOREIGN KEY (account_id, currency_code)
        REFERENCES public.accounts(id, currency_code)
);

CREATE INDEX journal_entries_transaction_idx
    ON public.journal_entries(transaction_id);

CREATE INDEX journal_entries_account_idx
    ON public.journal_entries(account_id);

CREATE TABLE public.idempotency_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope VARCHAR(150) NOT NULL CHECK (length(trim(scope)) > 0),
    idempotency_key VARCHAR(255) NOT NULL
        CHECK (length(trim(idempotency_key)) > 0),
    request_fingerprint VARCHAR(64) NOT NULL
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    transaction_id UUID NOT NULL REFERENCES public.journal_transactions(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (scope, idempotency_key)
);

CREATE TABLE public.reversal_audits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_transaction_id UUID NOT NULL
        REFERENCES public.journal_transactions(id),
    reversal_transaction_id UUID NOT NULL UNIQUE
        REFERENCES public.journal_transactions(id),
    authorized_by VARCHAR(150) NOT NULL CHECK (length(trim(authorized_by)) > 0),
    reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (original_transaction_id, reversal_transaction_id)
);

-- Currency code and referenced precision cannot be rewritten historically.
CREATE FUNCTION public.guard_currency_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    IF NEW.code IS DISTINCT FROM OLD.code THEN
        RAISE EXCEPTION 'Currency code is immutable';
    END IF;

    IF NEW.minor_unit_exponent IS DISTINCT FROM OLD.minor_unit_exponent
       AND EXISTS (
           SELECT 1 FROM public.journal_transactions
           WHERE currency_code = OLD.code
       ) THEN
        RAISE EXCEPTION 'Currency precision cannot change after financial transactions exist';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE TRIGGER currencies_guard_update
BEFORE UPDATE ON public.currencies
FOR EACH ROW EXECUTE FUNCTION public.guard_currency_update();

-- Account identity is stable; status changes remain permitted.
CREATE FUNCTION public.guard_account_identity()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    IF NEW.account_code IS DISTINCT FROM OLD.account_code
       OR NEW.account_class IS DISTINCT FROM OLD.account_class
       OR NEW.account_role IS DISTINCT FROM OLD.account_role
       OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Account identity and accounting classification are immutable';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE TRIGGER accounts_guard_identity
BEFORE UPDATE ON public.accounts
FOR EACH ROW EXECUTE FUNCTION public.guard_account_identity();

-- Posted financial records and exception audit records cannot be edited/deleted.
CREATE FUNCTION public.reject_financial_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    RAISE EXCEPTION 'Posted financial records and reversal audits are immutable';
END;
$function$;

CREATE TRIGGER journal_transactions_immutable
BEFORE UPDATE OR DELETE ON public.journal_transactions
FOR EACH ROW EXECUTE FUNCTION public.reject_financial_mutation();

CREATE TRIGGER journal_entries_immutable
BEFORE UPDATE OR DELETE ON public.journal_entries
FOR EACH ROW EXECUTE FUNCTION public.reject_financial_mutation();

CREATE TRIGGER reversal_audits_immutable
BEFORE UPDATE OR DELETE ON public.reversal_audits
FOR EACH ROW EXECUTE FUNCTION public.reject_financial_mutation();

-- The only runtime write path for financial postings.
CREATE FUNCTION public.post_journal_transaction(
    p_scope TEXT,
    p_idempotency_key TEXT,
    p_transaction_reference TEXT,
    p_currency_code VARCHAR(3),
    p_description TEXT,
    p_entries JSONB,
    p_reversal_of_transaction_id UUID DEFAULT NULL,
    p_authorized_by TEXT DEFAULT NULL,
    p_reason TEXT DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    v_request JSONB;
    v_fingerprint TEXT;
    v_existing_fingerprint TEXT;
    v_transaction_id UUID;
    v_enabled BOOLEAN;
    v_original_currency VARCHAR(3);
    v_original_reversal UUID;
    v_entry_count BIGINT;
    v_invalid_count BIGINT;
    v_debit_total NUMERIC;
    v_credit_total NUMERIC;
    v_requested_accounts BIGINT;
    v_found_accounts BIGINT;
    v_has_exception BOOLEAN;
    v_mismatch BOOLEAN;
BEGIN
    IF p_scope IS NULL OR length(trim(p_scope)) = 0
       OR p_idempotency_key IS NULL OR length(trim(p_idempotency_key)) = 0
       OR p_transaction_reference IS NULL OR length(trim(p_transaction_reference)) = 0
       OR p_description IS NULL OR length(trim(p_description)) = 0 THEN
        RAISE EXCEPTION 'Scope, idempotency key, transaction reference and description are required';
    END IF;

    IF p_entries IS NULL OR jsonb_typeof(p_entries) <> 'array' THEN
        RAISE EXCEPTION 'Entries must be a JSON array';
    END IF;

    v_request := jsonb_build_object(
        'scope', p_scope,
        'transaction_reference', p_transaction_reference,
        'currency_code', p_currency_code,
        'description', p_description,
        'entries', p_entries,
        'reversal_of_transaction_id', p_reversal_of_transaction_id,
        'authorized_by', p_authorized_by,
        'reason', p_reason
    );

    v_fingerprint := encode(
        public.digest(convert_to(v_request::TEXT, 'UTF8'), 'sha256'),
        'hex'
    );

    -- Serialize requests sharing the same idempotency scope and key.
    PERFORM pg_advisory_xact_lock(
        hashtextextended(p_scope || chr(31) || p_idempotency_key, 0)
    );

    SELECT transaction_id, request_fingerprint
      INTO v_transaction_id, v_existing_fingerprint
      FROM public.idempotency_records
     WHERE scope = p_scope AND idempotency_key = p_idempotency_key;

    IF FOUND THEN
        IF v_existing_fingerprint <> v_fingerprint THEN
            RAISE EXCEPTION 'Idempotency key reused with a different request';
        END IF;
        RETURN v_transaction_id;
    END IF;

    SELECT enabled INTO v_enabled
      FROM public.currencies
     WHERE code = p_currency_code
     FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unsupported currency: %', p_currency_code;
    END IF;

    IF p_reversal_of_transaction_id IS NULL AND NOT v_enabled THEN
        RAISE EXCEPTION 'Currency is disabled for new postings';
    END IF;

    IF p_reversal_of_transaction_id IS NOT NULL THEN
        SELECT currency_code, reversal_of_transaction_id
          INTO v_original_currency, v_original_reversal
          FROM public.journal_transactions
         WHERE id = p_reversal_of_transaction_id
         FOR UPDATE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Original transaction does not exist';
        END IF;

        IF v_original_reversal IS NOT NULL THEN
            RAISE EXCEPTION 'A reversal cannot itself be reversed';
        END IF;

        IF v_original_currency <> p_currency_code THEN
            RAISE EXCEPTION 'Reversal currency must match original transaction';
        END IF;

        IF EXISTS (
            SELECT 1 FROM public.journal_transactions
             WHERE reversal_of_transaction_id = p_reversal_of_transaction_id
        ) THEN
            RAISE EXCEPTION 'Original transaction has already been reversed';
        END IF;
    END IF;

    SELECT count(*),
           count(*) FILTER (
               WHERE account_id IS NULL OR direction IS NULL
                  OR amount_minor IS NULL OR amount_minor <= 0
                  OR direction NOT IN ('DEBIT','CREDIT')
           ),
           COALESCE(sum(amount_minor::NUMERIC)
               FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(sum(amount_minor::NUMERIC)
               FILTER (WHERE direction = 'CREDIT'), 0)
      INTO v_entry_count, v_invalid_count, v_debit_total, v_credit_total
      FROM jsonb_to_recordset(p_entries)
           AS x(account_id UUID, direction TEXT, amount_minor BIGINT);

    IF v_entry_count < 2 THEN
        RAISE EXCEPTION 'A posted transaction requires at least two entries';
    END IF;

    IF v_invalid_count > 0 THEN
        RAISE EXCEPTION 'Entries contain missing fields, invalid directions or non-positive amounts';
    END IF;

    IF v_debit_total <> v_credit_total THEN
        RAISE EXCEPTION 'Transaction is unbalanced: debits %, credits %',
            v_debit_total, v_credit_total;
    END IF;

    SELECT count(DISTINCT account_id)
      INTO v_requested_accounts
      FROM jsonb_to_recordset(p_entries)
           AS x(account_id UUID, direction TEXT, amount_minor BIGINT);

    -- Lock every affected account in UUID order to reduce deadlocks.
    PERFORM a.id
      FROM public.accounts a
      JOIN (
          SELECT DISTINCT account_id
            FROM jsonb_to_recordset(p_entries)
                 AS x(account_id UUID, direction TEXT, amount_minor BIGINT)
      ) requested ON requested.account_id = a.id
     ORDER BY a.id
     FOR UPDATE OF a;

    SELECT count(*)
      INTO v_found_accounts
      FROM public.accounts a
      JOIN (
          SELECT DISTINCT account_id
            FROM jsonb_to_recordset(p_entries)
                 AS x(account_id UUID, direction TEXT, amount_minor BIGINT)
      ) requested ON requested.account_id = a.id;

    IF v_found_accounts <> v_requested_accounts THEN
        RAISE EXCEPTION 'One or more referenced accounts do not exist';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM jsonb_to_recordset(p_entries)
               AS x(account_id UUID, direction TEXT, amount_minor BIGINT)
          JOIN public.accounts a ON a.id = x.account_id
         WHERE a.currency_code <> p_currency_code
    ) THEN
        RAISE EXCEPTION 'Entry account currency must match transaction currency';
    END IF;

    SELECT EXISTS (
        SELECT 1
          FROM jsonb_to_recordset(p_entries)
               AS x(account_id UUID, direction TEXT, amount_minor BIGINT)
          JOIN public.accounts a ON a.id = x.account_id
         WHERE a.status <> 'ACTIVE'
    ) INTO v_has_exception;

    IF p_reversal_of_transaction_id IS NULL AND v_has_exception THEN
        RAISE EXCEPTION 'Ordinary postings require ACTIVE accounts';
    END IF;

    IF v_has_exception AND (
        p_reversal_of_transaction_id IS NULL
        OR NOT pg_has_role(session_user, 'ledger_reversal_approver', 'member')
        OR p_authorized_by IS NULL OR length(trim(p_authorized_by)) = 0
        OR p_reason IS NULL OR length(trim(p_reason)) = 0
    ) THEN
        RAISE EXCEPTION 'Exceptional reversal requires approver role, authorizer and reason';
    END IF;

    IF p_reversal_of_transaction_id IS NOT NULL THEN
        WITH requested AS (
            SELECT account_id, direction, amount_minor, count(*) AS occurrences
              FROM jsonb_to_recordset(p_entries)
                   AS x(account_id UUID, direction TEXT, amount_minor BIGINT)
             GROUP BY account_id, direction, amount_minor
        ),
        expected AS (
            SELECT account_id,
                   CASE direction WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END AS direction,
                   amount_minor,
                   count(*) AS occurrences
              FROM public.journal_entries
             WHERE transaction_id = p_reversal_of_transaction_id
             GROUP BY account_id, direction, amount_minor
        )
        SELECT EXISTS (
            SELECT 1
              FROM requested r
              FULL JOIN expected e
                ON e.account_id = r.account_id
               AND e.direction = r.direction
               AND e.amount_minor = r.amount_minor
             WHERE r.occurrences IS DISTINCT FROM e.occurrences
        ) INTO v_mismatch;

        IF v_mismatch THEN
            RAISE EXCEPTION 'Reversal entries must exactly compensate the original transaction';
        END IF;
    END IF;

    INSERT INTO public.journal_transactions (
        transaction_reference, currency_code, description,
        posted_at, reversal_of_transaction_id
    ) VALUES (
        p_transaction_reference, p_currency_code, p_description,
        clock_timestamp(), p_reversal_of_transaction_id
    )
    RETURNING id INTO v_transaction_id;

    INSERT INTO public.journal_entries (
        transaction_id, account_id, currency_code, direction, amount_minor
    )
    SELECT v_transaction_id, x.account_id, p_currency_code,
           x.direction, x.amount_minor
      FROM jsonb_to_recordset(p_entries)
           AS x(account_id UUID, direction TEXT, amount_minor BIGINT);

    IF v_has_exception THEN
        INSERT INTO public.reversal_audits (
            original_transaction_id, reversal_transaction_id, authorized_by, reason
        ) VALUES (
            p_reversal_of_transaction_id, v_transaction_id,
            p_authorized_by, p_reason
        );
    END IF;

    INSERT INTO public.idempotency_records (
        scope, idempotency_key, request_fingerprint, transaction_id
    ) VALUES (
        p_scope, p_idempotency_key, v_fingerprint, v_transaction_id
    );

    RETURN v_transaction_id;
END;
$function$;

-- Give the controlled posting path a separate, non-login owner.
GRANT USAGE ON SCHEMA public TO ledger_executor;

GRANT SELECT ON public.currencies, public.accounts,
    public.journal_transactions, public.journal_entries,
    public.idempotency_records, public.reversal_audits
TO ledger_executor;

GRANT UPDATE (code) ON public.currencies TO ledger_executor;

GRANT UPDATE (status) ON public.accounts TO ledger_executor;
GRANT UPDATE (description) ON public.journal_transactions TO ledger_executor;

GRANT INSERT ON public.journal_transactions, public.journal_entries,
    public.idempotency_records, public.reversal_audits
TO ledger_executor;

GRANT CREATE ON SCHEMA public TO ledger_executor;

ALTER FUNCTION public.guard_currency_update() OWNER TO ledger_executor;
ALTER FUNCTION public.guard_account_identity() OWNER TO ledger_executor;
ALTER FUNCTION public.reject_financial_mutation() OWNER TO ledger_executor;
ALTER FUNCTION public.post_journal_transaction(
    TEXT, TEXT, TEXT, VARCHAR, TEXT, JSONB, UUID, TEXT, TEXT
) OWNER TO ledger_executor;

REVOKE CREATE ON SCHEMA public FROM ledger_executor;

REVOKE ALL ON FUNCTION public.guard_currency_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_account_identity() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.reject_financial_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.post_journal_transaction(
    TEXT, TEXT, TEXT, VARCHAR, TEXT, JSONB, UUID, TEXT, TEXT
) FROM PUBLIC;

REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
    ON public.currencies, public.accounts, public.journal_transactions,
       public.journal_entries, public.idempotency_records, public.reversal_audits
    FROM ledger_runtime;

GRANT SELECT ON public.currencies, public.accounts,
    public.journal_transactions, public.journal_entries
TO ledger_runtime;

GRANT EXECUTE ON FUNCTION public.post_journal_transaction(
    TEXT, TEXT, TEXT, VARCHAR, TEXT, JSONB, UUID, TEXT, TEXT
) TO ledger_runtime;

GRANT USAGE ON SCHEMA public TO ledger_reversal_approver;

GRANT EXECUTE ON FUNCTION public.post_journal_transaction(
    TEXT, TEXT, TEXT, VARCHAR, TEXT, JSONB, UUID, TEXT, TEXT
) TO ledger_reversal_approver;