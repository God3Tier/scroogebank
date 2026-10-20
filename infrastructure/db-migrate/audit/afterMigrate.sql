-- Gives the auditor and chain-verify their own audit-db logins (ADR-0022
-- decision 2). Flyway runs this after every migrate, once V1_schema.sql has
-- created the roles they join. The passwords are Flyway placeholders that the
-- auditor's db-migrate container fills from Secrets Manager, so they never
-- appear here, and setting them every time keeps them equal to the secrets.

-- Tasks that start together run this at once, and concurrent changes to one
-- role can fail. Flyway runs this file in one transaction, which holds the
-- lock until it ends.
SELECT pg_advisory_xact_lock(hashtext('db-migrate logins'));

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auditor_app') THEN
        CREATE ROLE auditor_app LOGIN IN ROLE auditor_service;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'chain_verify') THEN
        CREATE ROLE chain_verify LOGIN IN ROLE auditor_chain_verify;
    END IF;
END
$$;

ALTER ROLE auditor_app PASSWORD '${auditor_app_password}';
ALTER ROLE chain_verify PASSWORD '${chain_verify_password}';
