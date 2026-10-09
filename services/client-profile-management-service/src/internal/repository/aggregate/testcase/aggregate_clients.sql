-- Clients for the dashboard aggregates. Own agents and dates in 2031, so rows
-- from other packages' fixtures never land in these agents or this window.
-- Times are Singapore time (+08), the day boundary the figures use.
DELETE FROM clients WHERE client_id::text LIKE '00000000-0000-0000-0000-0000000005%';
INSERT INTO clients (
    client_id, first_name, last_name, date_of_birth, gender, email, phone_number,
    address, city, state, country, postal_code, verification_status,
    created_by_agent_id, created_at, updated_at, deleted_at
) VALUES
    -- dash-agent-a
    ('00000000-0000-0000-0000-000000000501', 'Ann', 'Tan', '1990-01-01', 'female', 'dash-501@example.test', '+6560000501',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100501', 'UNVERIFIED', 'dash-agent-a',
     '2031-09-02 10:00:00+08', '2031-09-02 10:00:00+08', NULL),
    ('00000000-0000-0000-0000-000000000502', 'Ben', 'Lim', '1990-01-01', 'male', 'dash-502@example.test', '+6560000502',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100502', 'verified', 'dash-agent-a',
     '2031-09-02 23:30:00+08', '2031-09-02 23:30:00+08', NULL),
    -- The last minute before the window: counted in totals, not in the window.
    ('00000000-0000-0000-0000-000000000503', 'Cai', 'Ng', '1990-01-01', 'female', 'dash-503@example.test', '+6560000503',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100503', 'UNVERIFIED', 'dash-agent-a',
     '2031-08-31 23:59:00+08', '2031-08-31 23:59:00+08', NULL),
    -- Deleted: never counted.
    ('00000000-0000-0000-0000-000000000504', 'Dee', 'Ong', '1990-01-01', 'female', 'dash-504@example.test', '+6560000504',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100504', 'UNVERIFIED', 'dash-agent-a',
     '2031-09-05 09:00:00+08', '2031-09-05 09:00:00+08', '2031-09-06 09:00:00+08'),
    -- dash-agent-b
    -- Midnight Singapore time is still 2 September in UTC: it belongs to 3 September.
    ('00000000-0000-0000-0000-000000000505', 'Eve', 'Koh', '1990-01-01', 'female', 'dash-505@example.test', '+6560000505',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100505', 'VERIFIED', 'dash-agent-b',
     '2031-09-03 00:00:00+08', '2031-09-03 00:00:00+08', NULL),
    ('00000000-0000-0000-0000-000000000506', 'Fay', 'Goh', '1990-01-01', 'female', 'dash-506@example.test', '+6560000506',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100506', 'UNVERIFIED', 'dash-agent-b',
     '2031-09-30 23:59:59+08', '2031-09-30 23:59:59+08', NULL),
    -- The first second after the window.
    ('00000000-0000-0000-0000-000000000507', 'Gus', 'Teo', '1990-01-01', 'male', 'dash-507@example.test', '+6560000507',
     '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100507', 'UNVERIFIED', 'dash-agent-b',
     '2031-10-01 00:00:00+08', '2031-10-01 00:00:00+08', NULL);
