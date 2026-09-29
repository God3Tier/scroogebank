TRUNCATE TABLE audit_log;

INSERT INTO audit_log (
    id, sequence_no, event_type, action, source_service, actor_id,
    timestamp, payload, parent_hash, hash
)
VALUES (
    '00000000-0000-0000-0000-000000000101',
    1,
    'CRUD',
    'CREATE',
    'CRM',
    'actor-crm',
    '2026-09-16 04:00:00',
    '{"lead":"lead-101"}',
    '',
    'crm-hash-1'
);
