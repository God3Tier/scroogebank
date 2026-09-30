TRUNCATE TABLE audit_log;

INSERT INTO audit_log (
    id, sequence_no, event_type, action, source_service, actor_id,
    timestamp, payload, parent_hash, hash
)
VALUES (
    '00000000-0000-0000-0000-000000000604',
    15,
    'AUTH',
    'LOGIN_FAILED',
    'AUDIT_SERVICE',
    'actor-only',
    '2026-09-16 06:15:00',
    '{}',
    '',
    'remove-only-hash'
);
