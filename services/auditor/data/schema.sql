DROP TABLE IF EXISTS corrupted_audit_log CASCADE;
DROP TABLE IF EXISTS audit_log CASCADE;

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    sequence_no BIGINT UNIQUE,
    event_id VARCHAR(64),
    event_type VARCHAR(50),
    action VARCHAR(100),
    source_service VARCHAR(50),
    actor_id VARCHAR(100),
    timestamp TIMESTAMP,
    payload JSONB,
    parent_hash VARCHAR(64),
    hash VARCHAR(64),
    CONSTRAINT audit_log_event_id_unique UNIQUE (event_id)
);

CREATE TABLE corrupted_audit_log (
    id UUID PRIMARY KEY,
    log_type JSON,
    date_of_detect TIMESTAMP
);
