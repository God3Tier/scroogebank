TRUNCATE TABLE audit_log;

INSERT INTO audit_log (
    id, sequence_no, event_id, event_type, action, source_service, actor_id,
    timestamp, payload, parent_hash, hash
)
VALUES (
    '00000000-0000-0000-0000-000000000401',
    1,
    'evt-existing',
    'AUTH',
    'LOGIN_FAILED',
    'AUDIT_SERVICE',
    'actor-1',
    '2026-09-16 05:00:00',
    '{}',
    '',
    'chain-hash-1'
);
