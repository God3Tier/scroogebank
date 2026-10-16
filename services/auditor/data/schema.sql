DROP TABLE IF EXISTS corrupted_audit_log CASCADE;
DROP TABLE IF EXISTS audit_log CASCADE;

-- The hash chain (ADR-0022 decision 1). Each row's hash covers all of its
-- fields and its parent's hash (src/internal/chain), computed over the row
-- exactly as stored here. Rows are never updated or deleted.
CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    sequence_no BIGINT NOT NULL UNIQUE,
    -- NULL when the event has none, never '', so the two cannot be swapped.
    event_id VARCHAR(64) CHECK (event_id <> ''),
    event_type VARCHAR(50) NOT NULL,
    action VARCHAR(100) NOT NULL,
    source_service VARCHAR(50) NOT NULL,
    actor_id VARCHAR(100) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    -- json, not jsonb: jsonb reorders keys and adds spaces, so the stored
    -- text would no longer be the text that was hashed.
    payload JSON NOT NULL,
    parent_hash VARCHAR(64) NOT NULL,
    hash VARCHAR(64) NOT NULL,
    CONSTRAINT audit_log_event_id_unique UNIQUE (event_id)
);

-- The activity feed lists one actor's entries, newest first (ADR-0021).
CREATE INDEX idx_audit_log_actor_sequence ON audit_log (actor_id, sequence_no DESC);

-- Tamper findings (ADR-0022 decision 4). chain-verify records each problem
-- once: audit_log_id and reason are unique together. Findings added through
-- POST /api/corrupted-logs have neither, so they never conflict.
CREATE TABLE corrupted_audit_log (
    id UUID PRIMARY KEY,
    -- The affected audit_log row. Not a foreign key: a finding must outlive
    -- whatever was done to the chain.
    audit_log_id UUID,
    reason VARCHAR(50),
    -- The affected row as chain-verify found it.
    log_type JSON,
    date_of_detect TIMESTAMPTZ,
    CONSTRAINT corrupted_audit_log_finding_unique UNIQUE (audit_log_id, reason)
);

-- Least privilege (ADR-0022 decision 2). The auditor and chain-verify each log
-- in as their own user, granted one of these roles; neither owns the tables.
-- Roles belong to the whole server, so they are created only once.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auditor_service') THEN
        CREATE ROLE auditor_service NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auditor_chain_verify') THEN
        CREATE ROLE auditor_chain_verify NOLOGIN;
    END IF;
END
$$;

-- The auditor appends to the chain and reads it, and serves the findings
-- endpoints (GET, POST and DELETE while open item 13 is open).
GRANT SELECT, INSERT ON audit_log TO auditor_service;
GRANT SELECT, INSERT, DELETE ON corrupted_audit_log TO auditor_service;

-- chain-verify reads the chain and records findings.
GRANT SELECT ON audit_log TO auditor_chain_verify;
GRANT SELECT, INSERT ON corrupted_audit_log TO auditor_chain_verify;
