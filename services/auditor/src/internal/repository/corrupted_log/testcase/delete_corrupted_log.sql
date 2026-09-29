TRUNCATE TABLE corrupted_audit_log;

INSERT INTO corrupted_audit_log (id, log_type, date_of_detect)
VALUES (
    '00000000-0000-0000-0000-000000000104',
    '{"Id":"audit-104"}',
    '2026-09-29 10:00:00'
);
