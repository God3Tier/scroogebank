TRUNCATE TABLE audit_log;

INSERT INTO audit_log (
    id, sequence_no, event_type, action, source_service, actor_id,
    timestamp, payload, parent_hash, hash
)
VALUES (
    '00000000-0000-0000-0000-000000000102',
    1,
    'AUTH',
    'LOGIN_FAILED',
    'USER_MANAGEMENT',
    'actor-user',
    '2026-09-16 04:00:00',
    '{"attempt":1}',
    '',
    'user-hash-1'
);
