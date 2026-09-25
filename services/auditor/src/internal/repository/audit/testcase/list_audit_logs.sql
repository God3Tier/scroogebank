TRUNCATE TABLE audit_log;

INSERT INTO audit_log (
    id, sequence_no, event_type, action, source_service, actor_id,
    timestamp, payload, parent_hash, hash
)
VALUES
    ('00000000-0000-0000-0000-000000000201', 1, 'CRUD', 'CREATE_CLIENT', 'CRM', 'agent-a',
     '2026-09-10 04:00:00', '{"client_id":"c-1"}', '', 'hash-1'),
    ('00000000-0000-0000-0000-000000000202', 2, 'CRUD', 'CREATE_CLIENT', 'CRM', 'agent-b',
     '2026-09-11 04:00:00', '{"client_id":"c-2"}', 'hash-1', 'hash-2'),
    ('00000000-0000-0000-0000-000000000203', 3, 'CRUD', 'CREATE_USER', 'USER_MANAGEMENT', 'agent-a',
     '2026-09-12 04:00:00', '{"user_id":"u-1"}', 'hash-2', 'hash-3'),
    ('00000000-0000-0000-0000-000000000204', 4, 'CRUD', 'UPDATE_CLIENT', 'CRM', 'agent-a',
     '2026-09-20 04:00:00', '{"client_id":"c-1"}', 'hash-3', 'hash-4');
