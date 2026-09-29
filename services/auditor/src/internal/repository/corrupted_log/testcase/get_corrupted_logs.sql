TRUNCATE TABLE corrupted_audit_log;

INSERT INTO corrupted_audit_log (id, log_type, date_of_detect)
VALUES
    (
        '00000000-0000-0000-0000-000000000102',
        '{"Id":"audit-102","SequenceNo":102,"EventType":"AUTH","Action":"LOGIN_FAILED"}',
        '2026-09-29 10:00:00'
    ),
    (
        '00000000-0000-0000-0000-000000000103',
        '[]',
        '2026-09-29 10:01:00'
    );
