TRUNCATE TABLE audit_log;

INSERT INTO audit_log (
    id, sequence_no, event_type, action, source_service, actor_id,
    timestamp, payload, parent_hash, hash
)
VALUES
    (
        '00000000-0000-0000-0000-000000000601',
        2,
        'AUTH',
        'LOGIN_FAILED',
        'AUDIT_SERVICE',
        'actor-2',
        '2026-09-16 06:00:00',
        '{}',
        '',
        'remove-hash-2'
    ),
    (
        '00000000-0000-0000-0000-000000000602',
        7,
        'AUTH',
        'LOGIN_FAILED',
        'AUDIT_SERVICE',
        'actor-7',
        '2026-09-16 06:05:00',
        '{}',
        'remove-hash-2',
        'remove-hash-7'
    ),
    (
        '00000000-0000-0000-0000-000000000603',
        11,
        'AUTH',
        'LOGIN_FAILED',
        'AUDIT_SERVICE',
        'actor-11',
        '2026-09-16 06:09:00',
        '{}',
        'remove-hash-7',
        'remove-hash-11'
    );
