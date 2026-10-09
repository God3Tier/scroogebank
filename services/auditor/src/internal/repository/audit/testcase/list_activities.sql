TRUNCATE TABLE audit_log;

-- Sequence numbers 1 to 4 and 11 are changes the activity feed shows. 5 to 10
-- are entries it leaves out: reads, a sign-out, a failed delete, a dashboard
-- view and an ingestion event.
INSERT INTO audit_log (
    id, sequence_no, event_type, action, source_service, actor_id,
    timestamp, payload, parent_hash, hash
)
VALUES
    ('00000000-0000-0000-0000-000000000201', 1, 'CRUD', 'CREATE', 'CRM', 'agent-a',
     '2026-09-10 04:00:00', '{"client_id":"c-1","status_code":201}', '', 'hash-1'),
    ('00000000-0000-0000-0000-000000000202', 2, 'CRUD', 'CREATE', 'CRM', 'agent-b',
     '2026-09-11 04:00:00', '{"client_id":"c-2","status_code":201}', 'hash-1', 'hash-2'),
    ('00000000-0000-0000-0000-000000000203', 3, 'CRUD', 'ROLE_CHANGE', 'USER_MANAGEMENT', 'admin-a',
     '2026-09-12 04:00:00', '{"resource_type":"USER","resource_id":"u-1","status_code":200}', 'hash-2', 'hash-3'),
    ('00000000-0000-0000-0000-000000000204', 4, 'CRUD', 'UPDATE', 'CRM', 'agent-a',
     '2026-09-20 04:00:00', '{"client_id":"c-1","status_code":200}', 'hash-3', 'hash-4'),
    ('00000000-0000-0000-0000-000000000205', 5, 'CRUD', 'GET', 'CRM', 'agent-a',
     '2026-09-21 04:00:00', '{"client_id":"c-1","status_code":200}', 'hash-4', 'hash-5'),
    ('00000000-0000-0000-0000-000000000206', 6, 'CRUD', 'READ', 'USER_MANAGEMENT', 'admin-a',
     '2026-09-21 05:00:00', '{"resource_type":"USER","resource_id":"u-1","status_code":200}', 'hash-5', 'hash-6'),
    ('00000000-0000-0000-0000-000000000207', 7, 'CRUD', 'LOGOUT', 'USER_MANAGEMENT', 'agent-a',
     '2026-09-21 06:00:00', '{"resource_type":"USER","resource_id":"u-2","status_code":204}', 'hash-6', 'hash-7'),
    ('00000000-0000-0000-0000-000000000208', 8, 'CRUD', 'DELETE', 'CRM', 'agent-a',
     '2026-09-21 07:00:00', '{"client_id":"c-1","status_code":409}', 'hash-7', 'hash-8'),
    ('00000000-0000-0000-0000-000000000209', 9, 'DASHBOARD_VIEW', 'VIEW_OVERVIEW', 'DASHBOARD', 'agent-a',
     '2026-09-21 08:00:00', '{"scope":"AGENT"}', 'hash-8', 'hash-9'),
    ('00000000-0000-0000-0000-000000000210', 10, 'TRANSACTION_INGESTION', 'FILE_PROCESSING_COMPLETED', 'TRANSACTION_SERVICE', 'system',
     '2026-09-21 09:00:00', '{"file_name":"t.csv"}', 'hash-9', 'hash-10'),
    -- An older-style payload with no status_code is kept.
    ('00000000-0000-0000-0000-000000000211', 11, 'CRUD', 'update', 'CRM', 'agent-b',
     '2026-09-22 04:00:00', '{"client_id":"c-2"}', 'hash-10', 'hash-11');
