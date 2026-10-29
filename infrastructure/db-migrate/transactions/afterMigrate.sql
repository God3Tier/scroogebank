-- Gives transaction-service and the ingest Lambda their own logins (ADR-0028
-- decision 3). Neither can change the schema, which the master user that runs
-- the migrations owns. transaction-service reads transactions and writes and
-- sends its own audit events; the ingest Lambda writes transactions and their
-- audit events. Each outbox has one writer and one sender (ADR-0064 decision
-- 5). Flyway runs this after every migrate, so a table that a later migration
-- adds is granted to the Lambda with no change here, unless it is revoked
-- below. The passwords are Flyway
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
GRANT SELECT, INSERT, DELETE ON read_audit_outbox TO transaction_service;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO transaction_ingest;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO transaction_ingest;
-- Only db-migrate records migrations, and only transaction-service uses its
-- outbox.
REVOKE ALL ON flyway_schema_history FROM transaction_ingest;
REVOKE ALL ON read_audit_outbox FROM transaction_ingest;
