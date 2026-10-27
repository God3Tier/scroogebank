-- A team for the comparison: own agents and dates in 2032, so no other
-- fixture's clients fall in the group. Window: June 2032. The group is agents
-- with a client created since 1 January 2032 that is not deleted.
DELETE FROM clients WHERE client_id::text LIKE '00000000-0000-0000-0000-0000000007%';
INSERT INTO clients (
    client_id, first_name, last_name, date_of_birth, gender, email, email_hash, phone_number, phone_number_hash,
    address, city, state, country, postal_code, verification_status,
    assigned_agent_id, created_at, updated_at, deleted_at
)
SELECT ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, 'Team', 'Client', DATE '1990-01-01', 'female',
       'team-' || n || '@example.test', 'email-hash-' || n, '+65600' || n, 'phone-hash-' || n, '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '100' || n,
       'UNVERIFIED', agent, created, created, deleted
FROM (VALUES
    -- team-1: 3 in June.
    (701, 'team-1', TIMESTAMPTZ '2032-06-02 10:00+08', NULL::timestamptz),
    (702, 'team-1', TIMESTAMPTZ '2032-06-10 10:00+08', NULL),
    (703, 'team-1', TIMESTAMPTZ '2032-06-30 23:00+08', NULL),
    -- team-2: 1 in June.
    (704, 'team-2', TIMESTAMPTZ '2032-06-05 10:00+08', NULL),
    -- team-3: 2 in June.
    (705, 'team-3', TIMESTAMPTZ '2032-06-06 10:00+08', NULL),
    (706, 'team-3', TIMESTAMPTZ '2032-06-07 10:00+08', NULL),
    -- team-4: in the group through a February client, with none in June.
    (707, 'team-4', TIMESTAMPTZ '2032-02-01 10:00+08', NULL),
    -- team-5: 4 in June.
    (708, 'team-5', TIMESTAMPTZ '2032-06-11 10:00+08', NULL),
    (709, 'team-5', TIMESTAMPTZ '2032-06-12 10:00+08', NULL),
    (710, 'team-5', TIMESTAMPTZ '2032-06-13 10:00+08', NULL),
    (711, 'team-5', TIMESTAMPTZ '2032-06-14 10:00+08', NULL),
    -- team-6: only a client from before the group's cut-off: not in the group.
    (712, 'team-6', TIMESTAMPTZ '2031-12-31 10:00+08', NULL),
    -- team-7: only a deleted client: not in the group.
    (713, 'team-7', TIMESTAMPTZ '2032-06-15 10:00+08', TIMESTAMPTZ '2032-06-16 10:00+08')
) AS team(n, agent, created, deleted);
