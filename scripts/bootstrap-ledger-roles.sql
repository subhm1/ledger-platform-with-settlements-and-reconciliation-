-- Run as a trusted PostgreSQL administrator before applying V2.
-- This creates only the non-login approval role.
-- It does not grant approval authority to any login principal.

DO $bootstrap$
DECLARE
    v_can_login BOOLEAN;
    v_is_superuser BOOLEAN;
    v_can_createdb BOOLEAN;
    v_can_createrole BOOLEAN;
    v_can_replicate BOOLEAN;
    v_bypasses_rls BOOLEAN;
BEGIN
    SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
           rolreplication, rolbypassrls
      INTO v_can_login, v_is_superuser, v_can_createdb, v_can_createrole,
           v_can_replicate, v_bypasses_rls
      FROM pg_catalog.pg_roles
     WHERE rolname = 'ledger_reversal_approver';

    IF NOT FOUND THEN
        EXECUTE 'CREATE ROLE ledger_reversal_approver
                 WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
                 NOREPLICATION NOBYPASSRLS';
    ELSIF v_can_login OR v_is_superuser OR v_can_createdb
       OR v_can_createrole OR v_can_replicate OR v_bypasses_rls THEN
        RAISE EXCEPTION
            'Existing ledger_reversal_approver role has unsafe attributes';
    END IF;
END
$bootstrap$;
