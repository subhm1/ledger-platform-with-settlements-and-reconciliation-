-- Local review operator provisioning only. The PowerShell wrapper verifies
-- the Compose project and this transaction verifies database and role.
BEGIN;
SET LOCAL ROLE ledger_owner;

DO $provision$
BEGIN
    IF current_database() <> 'ledger' OR current_user <> 'ledger_owner' THEN
        RAISE EXCEPTION 'INR provisioning requires the ledger database and ledger_owner role';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.currencies
        WHERE code = 'INR'
          AND (minor_unit_exponent <> 2 OR enabled IS DISTINCT FROM TRUE)
    ) THEN
        RAISE EXCEPTION 'INR currency record has unexpected attributes; inspect it before provisioning';
    END IF;
END;
$provision$;

INSERT INTO public.currencies (code, minor_unit_exponent, enabled)
VALUES ('INR', 2, TRUE)
ON CONFLICT (code) DO NOTHING;

DO $verify$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.currencies
        WHERE code = 'INR' AND minor_unit_exponent = 2 AND enabled IS TRUE
    ) THEN
        RAISE EXCEPTION 'INR provisioning did not result in the approved currency record';
    END IF;
END;
$verify$;

COMMIT;
