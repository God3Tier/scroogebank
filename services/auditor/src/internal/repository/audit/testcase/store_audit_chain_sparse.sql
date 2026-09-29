TRUNCATE TABLE audit_log;

INSERT INTO audit_log (
    id, sequence_no, event_type, action, source_service, actor_id,
    timestamp, payload, parent_hash, hash
)
VALUES
    (
        '00000000-0000-0000-0000-000000000501',
        2,
        'AUTH',
        'LOGIN_FAILED',
        'AUDIT_SERVICE',
        'actor-2',
        '2026-09-16 05:00:00',
        '{}',
        '',
        'sparse-parent-hash'
    ),
    (
        '00000000-0000-0000-0000-000000000502',
        7,
        'AUTH',
        'LOGIN_FAILED',
        'AUDIT_SERVICE',
        'actor-7',
        '2026-09-16 05:05:00',
        '{}',
        'sparse-parent-hash',
        'sparse-head-hash'
    );
