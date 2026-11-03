-- Local review only: provision five deterministic demo INR wallets and their
-- simulated opening balances. Invoke only through the guarded PowerShell wrapper.
BEGIN;
SET LOCAL ROLE ledger_owner;
SELECT pg_catalog.set_config(
    'ledger.demo_wallet_initial_balance_paise',
    :'initial_balance_paise'::TEXT,
    TRUE
);

DO $provision$
DECLARE
    v_amount BIGINT;
    v_wallets TEXT[] := ARRAY[
        'demo:shubham',
        'demo:ashwin',
        'demo:arnav',
        'demo:shreya',
        'demo:swati'
    ];
    v_owner TEXT;
    v_currency_exponent SMALLINT;
    v_treasury_id UUID;
    v_wallet_id UUID;
    v_wallet_account_id UUID;
    v_wallet_role TEXT;
    v_wallet_currency TEXT;
    v_entries JSONB;
BEGIN
    IF current_database() <> 'ledger' OR current_user <> 'ledger_owner' THEN
        RAISE EXCEPTION 'Demo wallet provisioning requires ledger database and ledger_owner role';
    END IF;

    v_amount := current_setting('ledger.demo_wallet_initial_balance_paise')::BIGINT;
    IF v_amount <= 0 THEN
        RAISE EXCEPTION 'Initial demo balance must be positive paise';
    END IF;

    SELECT minor_unit_exponent
      INTO v_currency_exponent
      FROM public.currencies
     WHERE code = 'INR' AND enabled IS TRUE;
    IF NOT FOUND OR v_currency_exponent <> 2 THEN
        RAISE EXCEPTION 'INR must already be enabled with minor-unit exponent 2';
    END IF;

    INSERT INTO public.accounts (
        account_code, account_class, account_role, currency_code, status
    ) VALUES (
        'DEMO:TREASURY:INR', 'ASSET', 'SETTLEMENT_CASH', 'INR', 'ACTIVE'
    ) ON CONFLICT (account_code) DO NOTHING;

    SELECT id INTO v_treasury_id
      FROM public.accounts
     WHERE account_code = 'DEMO:TREASURY:INR'
       AND account_class = 'ASSET'
       AND account_role = 'SETTLEMENT_CASH'
       AND currency_code = 'INR'
       AND status = 'ACTIVE';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Existing demo treasury account has unexpected attributes; inspect before provisioning';
    END IF;

    FOREACH v_owner IN ARRAY v_wallets LOOP
        SELECT id INTO v_wallet_id
          FROM public.wallets
         WHERE owner_reference = v_owner AND currency_code = 'INR';

        IF NOT FOUND THEN
            v_wallet_id := public.create_wallet(v_owner, 'INR');
        END IF;

        SELECT w.account_id, a.account_role, a.currency_code
          INTO v_wallet_account_id, v_wallet_role, v_wallet_currency
          FROM public.wallets w
          JOIN public.accounts a ON a.id = w.account_id
         WHERE w.id = v_wallet_id
           AND w.owner_reference = v_owner
           AND w.currency_code = 'INR'
           AND w.status = 'ACTIVE';
        IF NOT FOUND OR v_wallet_role <> 'CUSTOMER_WALLET'
                    OR v_wallet_currency <> 'INR' THEN
            RAISE EXCEPTION 'Existing wallet % has unexpected attributes; inspect before provisioning', v_owner;
        END IF;

        v_entries := pg_catalog.jsonb_build_array(
            pg_catalog.jsonb_build_object(
                'account_id', v_treasury_id,
                'direction', 'DEBIT',
                'amount_minor', v_amount
            ),
            pg_catalog.jsonb_build_object(
                'account_id', v_wallet_account_id,
                'direction', 'CREDIT',
                'amount_minor', v_amount
            )
        );

        PERFORM public.post_journal_transaction(
            'local-review-demo-wallet-opening-v1',
            'opening:' || v_owner || ':INR',
            'demo-wallet-opening-v1:' || v_owner || ':INR',
            'INR',
            'Simulated opening balance for ' || v_owner,
            v_entries,
            NULL::UUID,
            NULL::TEXT,
            NULL::TEXT
        );
    END LOOP;
END;
$provision$;

COMMIT;
