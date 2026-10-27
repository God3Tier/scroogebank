-- Clients for the dashboard aggregates. Own agents and dates in 2031, so rows
-- from other packages' fixtures never land in these agents or this window.
-- Times are Singapore time (+08), the day boundary the figures use.
-- Names and contact details are plain text here: the repository returns them
-- as stored, and the service decrypts them (ADR-0030). The hashes only need
-- to be unique.
DELETE FROM clients WHERE client_id::text LIKE '00000000-0000-0000-0000-0000000005%';
INSERT INTO clients (
    client_id, first_name, last_name, date_of_birth, gender, email, email_hash, phone_number, phone_number_hash,
    address, city, state, country, postal_code, verification_status,
    assigned_agent_id, created_at, updated_at, deleted_at
) VALUES
    -- dash-agent-a
    ('00000000-0000-0000-0000-000000000501', 'Ann', 'Tan', '1990-01-01', 'female', 'dash-501@example.test', 'email-hash-501', '+6560000501', 'phone-hash-501',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100501', 'UNVERIFIED', 'dash-agent-a',
     '2031-09-02 10:00:00+08', '2031-09-02 10:00:00+08', NULL),
    ('00000000-0000-0000-0000-000000000502', 'Ben', 'Lim', '1990-01-01', 'male', 'dash-502@example.test', 'email-hash-502', '+6560000502', 'phone-hash-502',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100502', 'verified', 'dash-agent-a',
     '2031-09-02 23:30:00+08', '2031-09-02 23:30:00+08', NULL),
    -- The last minute before the window: counted in totals, not in the window.
    ('00000000-0000-0000-0000-000000000503', 'Cai', 'Ng', '1990-01-01', 'female', 'dash-503@example.test', 'email-hash-503', '+6560000503', 'phone-hash-503',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100503', 'UNVERIFIED', 'dash-agent-a',
     '2031-08-31 23:59:00+08', '2031-08-31 23:59:00+08', NULL),
    -- Deleted: never counted.
    ('00000000-0000-0000-0000-000000000504', 'Dee', 'Ong', '1990-01-01', 'female', 'dash-504@example.test', 'email-hash-504', '+6560000504', 'phone-hash-504',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100504', 'UNVERIFIED', 'dash-agent-a',
     '2031-09-05 09:00:00+08', '2031-09-05 09:00:00+08', '2031-09-06 09:00:00+08'),
    -- dash-agent-b
    -- Midnight Singapore time is still 2 September in UTC: it belongs to 3 September.
    ('00000000-0000-0000-0000-000000000505', 'Eve', 'Koh', '1990-01-01', 'female', 'dash-505@example.test', 'email-hash-505', '+6560000505', 'phone-hash-505',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100505', 'VERIFIED', 'dash-agent-b',
     '2031-09-03 00:00:00+08', '2031-09-03 00:00:00+08', NULL),
    ('00000000-0000-0000-0000-000000000506', 'Fay', 'Goh', '1990-01-01', 'female', 'dash-506@example.test', 'email-hash-506', '+6560000506', 'phone-hash-506',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100506', 'UNVERIFIED', 'dash-agent-b',
     '2031-09-30 23:59:59+08', '2031-09-30 23:59:59+08', NULL),
    -- The first second after the window.
    ('00000000-0000-0000-0000-000000000507', 'Gus', 'Teo', '1990-01-01', 'male', 'dash-507@example.test', 'email-hash-507', '+6560000507', 'phone-hash-507',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100507', 'UNVERIFIED', 'dash-agent-b',
     '2031-10-01 00:00:00+08', '2031-10-01 00:00:00+08', NULL);
