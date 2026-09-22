TRUNCATE TABLE audit_log;

INSERT INTO audit_log (
    id, sequence_no, event_type, action, source_service, actor_id,
    timestamp, payload, parent_hash, hash
)
VALUES
    (
        '00000000-0000-0000-0000-000000000201',
        1,
        'AUTH',
        'LOGIN_FAILED',
        'AUDIT_SERVICE',
        'actor-1',
        '2026-09-16 05:00:00',
        '{}',
        '',
        'chain-hash-1'
    ),
    (
        '00000000-0000-0000-0000-000000000202',
        2,
        'AUTH',
        'LOGIN_FAILED',
        'AUDIT_SERVICE',
        'actor-2',
        '2026-09-16 05:01:00',
        '{}',
        'chain-hash-1',
        'chain-hash-2'
    ),
    (
        '00000000-0000-0000-0000-000000000203',
        3,
        'AUTH',
        'LOGIN_FAILED',
        'AUDIT_SERVICE',
        'actor-3',
        '2026-09-16 05:02:00',
        '{}',
        'chain-hash-2',
        'chain-hash-3'
    );
