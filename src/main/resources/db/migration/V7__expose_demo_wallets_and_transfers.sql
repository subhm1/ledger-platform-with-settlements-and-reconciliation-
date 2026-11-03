-- Phase 7: expose only the seeded demo wallet roster and route demo transfers
-- through the existing V3 transfer function and its database invariants.
-- V1-V6 remain immutable.

CREATE FUNCTION public.list_demo_wallets()
RETURNS TABLE (
    wallet_id UUID,
    owner_reference VARCHAR(150),
    currency_code VARCHAR(3),
    minor_unit_exponent SMALLINT,
    wallet_status VARCHAR(10),
    account_status VARCHAR(10),
    balance_minor TEXT
)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
    SELECT w.id,
           w.owner_reference,
           w.currency_code,
           c.minor_unit_exponent,
           w.status,
           a.status,
           COALESCE(
               SUM(CASE e.direction
                   WHEN 'CREDIT' THEN e.amount_minor::NUMERIC
                   ELSE -e.amount_minor::NUMERIC
               END),
               0
           )::TEXT
      FROM public.wallets w
      JOIN public.accounts a ON a.id = w.account_id
      JOIN public.currencies c ON c.code = w.currency_code
      LEFT JOIN public.journal_entries e ON e.account_id = w.account_id
     WHERE w.owner_reference IN (
         'demo:shubham', 'demo:ashwin', 'demo:arnav', 'demo:shreya', 'demo:swati'
     )
     GROUP BY w.id, c.minor_unit_exponent, a.status
     ORDER BY CASE w.owner_reference
         WHEN 'demo:shubham' THEN 1
         WHEN 'demo:ashwin' THEN 2
         WHEN 'demo:arnav' THEN 3
         WHEN 'demo:shreya' THEN 4
         WHEN 'demo:swati' THEN 5
     END;
$function$;

CREATE FUNCTION public.transfer_between_demo_wallets(
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
    v_demo_wallet_count INTEGER;
BEGIN
    IF p_source_wallet_id IS NULL OR p_destination_wallet_id IS NULL THEN
        RAISE EXCEPTION 'Source and destination demo wallets are required';
    END IF;

    IF p_source_wallet_id = p_destination_wallet_id THEN
        RAISE EXCEPTION 'Source and destination demo wallets must differ';
    END IF;

    SELECT count(*)
      INTO v_demo_wallet_count
      FROM public.wallets w
     WHERE w.id IN (p_source_wallet_id, p_destination_wallet_id)
       AND w.owner_reference IN (
           'demo:shubham', 'demo:ashwin', 'demo:arnav', 'demo:shreya', 'demo:swati'
       )
       AND w.currency_code = 'INR';

    IF v_demo_wallet_count <> 2 THEN
        RAISE EXCEPTION 'Transfers are limited to the five seeded INR demo wallets';
    END IF;

    RETURN public.transfer_between_wallets(
        p_scope,
        p_idempotency_key,
        p_transaction_reference,
        p_source_wallet_id,
        p_destination_wallet_id,
        p_amount_minor,
        p_description
    );
END;
$function$;

GRANT CREATE ON SCHEMA public TO ledger_executor;
ALTER FUNCTION public.list_demo_wallets() OWNER TO ledger_executor;
ALTER FUNCTION public.transfer_between_demo_wallets(
    TEXT, TEXT, TEXT, UUID, UUID, BIGINT, TEXT
) OWNER TO ledger_executor;
REVOKE CREATE ON SCHEMA public FROM ledger_executor;

REVOKE ALL ON FUNCTION public.list_demo_wallets() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.transfer_between_demo_wallets(
    TEXT, TEXT, TEXT, UUID, UUID, BIGINT, TEXT
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.list_demo_wallets() TO ledger_runtime;
GRANT EXECUTE ON FUNCTION public.transfer_between_demo_wallets(
    TEXT, TEXT, TEXT, UUID, UUID, BIGINT, TEXT
) TO ledger_runtime;
