-- Gives transaction-service and the ingest Lambda their own logins (ADR-0028
-- decision 3). Neither can change the schema, which the master user that runs
-- the migrations owns. transaction-service checks no token, so it may only
-- read transactions; the ingest Lambda writes them and their audit events.
-- Flyway runs this after every migrate, so a table that a later migration
-- adds is granted to the Lambda with no change here. The passwords are Flyway
-- placeholders that transaction-service's db-migrate container fills from
-- Secrets Manager, so they never appear here, and setting them every time
-- keeps them equal to the secrets.

-- Tasks that start together run this at once, and concurrent GRANTs on one
-- table can fail. Flyway runs this file in one transaction, which holds the
-- lock until it ends.
SELECT pg_advisory_xact_lock(hashtext('db-migrate logins'));

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'transaction_service') THEN
        CREATE ROLE transaction_service LOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'transaction_ingest') THEN
        CREATE ROLE transaction_ingest LOGIN;
    END IF;
END
$$;

ALTER ROLE transaction_service PASSWORD '${transaction_service_password}';
ALTER ROLE transaction_ingest PASSWORD '${transaction_ingest_password}';

GRANT SELECT ON transactions TO transaction_service;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO transaction_ingest;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO transaction_ingest;
-- Only db-migrate records migrations.
REVOKE ALL ON flyway_schema_history FROM transaction_ingest;
