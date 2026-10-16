-- Creates the auditor's and chain-verify's audit-db logins (ADR-0022
-- decision 2), once, after services/auditor/data/schema.sql. The passwords
-- come from Secrets Manager inside the db-console task, never from here:
--   scripts/db-run-sql.sh audit infrastructure/terraform/sql/audit-logins.sql --expand-env
CREATE ROLE auditor_app LOGIN PASSWORD '${AUDITOR_APP_PASSWORD}' IN ROLE auditor_service;
CREATE ROLE chain_verify LOGIN PASSWORD '${CHAIN_VERIFY_PASSWORD}' IN ROLE auditor_chain_verify;
