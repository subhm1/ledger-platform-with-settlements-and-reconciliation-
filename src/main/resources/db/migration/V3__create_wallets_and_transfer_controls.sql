-- Phase 3: Wallet foundation and controlled transfers.
-- V1 and V2 remain immutable.

CREATE TABLE public.wallets (
    id UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    owner_reference VARCHAR(150) NOT NULL
        CHECK (length(trim(owner_reference)) > 0),
    currency_code VARCHAR(3) NOT NULL
        REFERENCES public.currencies(code),
    account_id UUID NOT NULL UNIQUE,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_reference, currency_code),
    FOREIGN KEY (account_id, currency_code)
        REFERENCES public.accounts(id, currency_code)
);

CREATE INDEX wallets_owner_reference_idx
    ON public.wallets(owner_reference);

-- Creates one customer-wallet liability account and its wallet record.
CREATE FUNCTION public.create_wallet(
    p_owner_reference TEXT,
    p_currency_code VARCHAR(3)
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    v_wallet_id UUID;
    v_account_id UUID;
    v_account_code TEXT;
BEGIN
    IF p_owner_reference IS NULL
       OR length(trim(p_owner_reference)) = 0 THEN
        RAISE EXCEPTION 'Owner reference is required';
    END IF;

    IF p_currency_code IS NULL THEN
        RAISE EXCEPTION 'Currency code is required';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM public.currencies
         WHERE code = p_currency_code
           AND enabled = TRUE
    ) THEN
        RAISE EXCEPTION 'Currency is missing or disabled';
    END IF;

    v_wallet_id := pg_catalog.gen_random_uuid();
    v_account_id := pg_catalog.gen_random_uuid();
    v_account_code := 'WALLET:' || v_wallet_id::TEXT;

    INSERT INTO public.accounts (
        id, account_code, account_class, account_role,
        currency_code, status
    )
    VALUES (
        v_account_id, v_account_code, 'LIABILITY',
        'CUSTOMER_WALLET', p_currency_code, 'ACTIVE'
    );

    INSERT INTO public.wallets (
        id, owner_reference, currency_code, account_id
    )
    VALUES (
        v_wallet_id, trim(p_owner_reference),
        p_currency_code, v_account_id
    );

    RETURN v_wallet_id;
END;
$function$;

-- Wallet transfers use the existing posting function and its idempotency
-- contract. Lock wallet rows in UUID order to avoid opposite-transfer deadlocks.
CREATE FUNCTION public.transfer_between_wallets(
    p_scope TEXT,
    p_idempotency_key TEXT,
    p_transaction_reference TEXT,
    p_source_wallet_id UUID,
    p_destination_wallet_id UUID,
    p_amount_minor BIGINT,
    p_description TEXT
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    v_wallet RECORD;
    v_wallet_count INTEGER := 0;
    v_source_account UUID;
    v_destination_account UUID;
    v_source_currency VARCHAR(3);
    v_destination_currency VARCHAR(3);
BEGIN
    IF p_scope IS NULL OR length(trim(p_scope)) = 0
       OR p_idempotency_key IS NULL
       OR length(trim(p_idempotency_key)) = 0
       OR p_transaction_reference IS NULL
       OR length(trim(p_transaction_reference)) = 0
       OR p_description IS NULL
       OR length(trim(p_description)) = 0 THEN
        RAISE EXCEPTION 'Transfer fields must not be blank';
    END IF;

    IF p_source_wallet_id IS NULL
       OR p_destination_wallet_id IS NULL THEN
        RAISE EXCEPTION 'Source and destination wallets are required';
    END IF;

    IF p_source_wallet_id = p_destination_wallet_id THEN
        RAISE EXCEPTION 'Source and destination wallets must differ';
    END IF;

    IF p_amount_minor IS NULL OR p_amount_minor <= 0 THEN
        RAISE EXCEPTION 'Transfer amount must be positive';
    END IF;

    FOR v_wallet IN
        SELECT w.id, w.account_id, w.currency_code
          FROM public.wallets w
         WHERE w.id IN (p_source_wallet_id, p_destination_wallet_id)
         ORDER BY w.id
         FOR UPDATE
    LOOP
        v_wallet_count := v_wallet_count + 1;

        IF v_wallet.id = p_source_wallet_id THEN
            v_source_account := v_wallet.account_id;
            v_source_currency := v_wallet.currency_code;
        ELSE
            v_destination_account := v_wallet.account_id;
            v_destination_currency := v_wallet.currency_code;
        END IF;
    END LOOP;

    IF v_wallet_count <> 2 THEN
        RAISE EXCEPTION 'Source or destination wallet was not found';
    END IF;

    IF v_source_currency <> v_destination_currency THEN
        RAISE EXCEPTION 'Wallet currencies must match';
    END IF;

    RETURN public.post_journal_transaction(
        trim(p_scope),
        trim(p_idempotency_key),
        trim(p_transaction_reference),
        v_source_currency,
        trim(p_description),
        pg_catalog.jsonb_build_array(
            pg_catalog.jsonb_build_object(
                'account_id', v_source_account,
                'direction', 'DEBIT',
                'amount_minor', p_amount_minor
            ),
            pg_catalog.jsonb_build_object(
                'account_id', v_destination_account,
                'direction', 'CREDIT',
                'amount_minor', p_amount_minor
            )
        ),
        NULL::UUID,
        NULL::TEXT,
        NULL::TEXT
    );
END;
$function$;

-- Deferred wallet guard checks the full transaction, not individual entries.
GRANT SELECT ON public.wallets TO ledger_executor;
GRANT INSERT ON public.wallets TO ledger_executor;
GRANT INSERT ON public.accounts TO ledger_executor;
GRANT UPDATE (status) ON public.wallets TO ledger_executor;

CREATE FUNCTION public.enforce_wallet_nonnegative_balance()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    v_wallet_status VARCHAR(10);
    v_prior_balance NUMERIC;
    v_transaction_delta NUMERIC;
BEGIN
    SELECT w.status
      INTO v_wallet_status
      FROM public.wallets w
     WHERE w.account_id = NEW.account_id;

    -- Non-wallet ledger accounts are not subject to wallet balance rules.
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF v_wallet_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'Wallet is not active';
    END IF;

    SELECT COALESCE(
               SUM(
                   CASE e.direction
                       WHEN 'CREDIT' THEN e.amount_minor::NUMERIC
                       ELSE -e.amount_minor::NUMERIC
                   END
               ),
               0
           )
      INTO v_prior_balance
      FROM public.journal_entries e
     WHERE e.account_id = NEW.account_id
       AND e.transaction_id <> NEW.transaction_id;

    SELECT COALESCE(
               SUM(
                   CASE e.direction
                       WHEN 'CREDIT' THEN e.amount_minor::NUMERIC
                       ELSE -e.amount_minor::NUMERIC
                   END
               ),
               0
           )
      INTO v_transaction_delta
      FROM public.journal_entries e
     WHERE e.account_id = NEW.account_id
       AND e.transaction_id = NEW.transaction_id;

    IF v_prior_balance + v_transaction_delta < 0 THEN
        RAISE EXCEPTION 'Insufficient wallet funds';
    END IF;

    RETURN NULL;
END;
$function$;

-- Temporarily grant CREATE so ownership can be transferred to ledger_executor.
GRANT CREATE ON SCHEMA public TO ledger_executor;

ALTER FUNCTION public.create_wallet(TEXT, VARCHAR)
    OWNER TO ledger_executor;

ALTER FUNCTION public.transfer_between_wallets(
    TEXT, TEXT, TEXT, UUID, UUID, BIGINT, TEXT
) OWNER TO ledger_executor;

ALTER FUNCTION public.enforce_wallet_nonnegative_balance()
    OWNER TO ledger_executor;

REVOKE CREATE ON SCHEMA public FROM ledger_executor;

REVOKE ALL ON FUNCTION public.create_wallet(TEXT, VARCHAR) FROM PUBLIC;

REVOKE ALL ON FUNCTION public.transfer_between_wallets(
    TEXT, TEXT, TEXT, UUID, UUID, BIGINT, TEXT
) FROM PUBLIC;

REVOKE ALL ON FUNCTION public.enforce_wallet_nonnegative_balance()
    FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.create_wallet(TEXT, VARCHAR)
    TO ledger_runtime;

GRANT EXECUTE ON FUNCTION public.transfer_between_wallets(
    TEXT, TEXT, TEXT, UUID, UUID, BIGINT, TEXT
) TO ledger_runtime;

CREATE CONSTRAINT TRIGGER wallet_nonnegative_balance_guard
AFTER INSERT ON public.journal_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION public.enforce_wallet_nonnegative_balance();