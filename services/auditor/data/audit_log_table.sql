DROP TABLE IF EXISTS audit_log CASCADE;

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    sequence_no BIGINT UNIQUE,        -- strict ordering, drives the chain
    event_type VARCHAR(50),           -- 'TRANSACTION_INGESTION', 'CRUD', 'USER_SPENDING', 'AUTH', ...
    action VARCHAR(100),              -- 'CREATE_LEAD', 'FILE_PROCESSED', 'LOGIN_FAILED', ...
    source_service VARCHAR(50),       -- which microservice emitted this
    actor_id VARCHAR(100),            -- user or system identity, nullable
    timestamp TIMESTAMP,
    payload JSONB,                    -- event-specific detail, schema varies by event_type
    parent_hash VARCHAR(64),
    hash VARCHAR(64)
);
