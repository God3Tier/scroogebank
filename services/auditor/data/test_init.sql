DROP TABLE IF EXISTS audit_log CASCADE;

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    sequence_no BIGINT UNIQUE,
    event_type VARCHAR(50),
    action VARCHAR(100),
    source_service VARCHAR(50),
    actor_id VARCHAR(100),
    timestamp TIMESTAMP,
    payload JSONB,
    parent_hash VARCHAR(64),
    hash VARCHAR(64)
);

INSERT INTO audit_log (
    id,
    sequence_no,
    event_type,
    action,
    source_service,
    actor_id,
    timestamp,
    payload,
    parent_hash,
    hash
)
VALUES
    (
        '00000000-0000-0000-0000-000000000001',
        1,
        'AUTH',
        'LOGIN_FAILED',
        'CRM',
        'actor-1',
        '2026-09-16 04:00:00',
        '{"ip":"127.0.0.1","attempt":1}',
        '',
        'hash-1'
    ),
    (
        '00000000-0000-0000-0000-000000000002',
        2,
        'CRUD',
        'CREATE_LEAD',
        'CRM',
        'actor-2',
        '2026-09-16 04:01:00',
        '{"lead":"lead-1","amount":42}',
        'hash-1',
        'hash-2'
    ),
    (
        '00000000-0000-0000-0000-000000000003',
        3,
        'TRANSACTION_INGESTION',
        'FILE_PROCESSED',
        'TRANSACTION_SERVICE',
        'system-1',
        '2026-09-16 04:02:00',
        '{"file":"batch-1.csv"}',
        'hash-2',
        'hash-3'
    );
