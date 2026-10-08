-- One client with accounts recorded under two owners. In practice every
-- account of a client carries that client's owner; two owners here only prove
-- that the agent-scoped query filters on owner_agent_id. Account 0234 is
-- deleted, so no list returns it.
DELETE FROM accounts WHERE client_id IN ('00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000204');
INSERT INTO accounts (id, client_id, owner_agent_id, account_type, status, opening_date, initial_deposit, currency, branch_id, created_at, updated_at, deleted_at)
VALUES
    ('00000000-0000-0000-0000-000000000231', '00000000-0000-0000-0000-000000000203', 'agent-sub-a', 'savings',  'active', DATE '2026-01-01', 100.00, 'SGD', 'SG-001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL),
    ('00000000-0000-0000-0000-000000000232', '00000000-0000-0000-0000-000000000203', 'agent-sub-a', 'checking', 'active', DATE '2026-02-01', 200.00, 'SGD', 'SG-001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL),
    ('00000000-0000-0000-0000-000000000233', '00000000-0000-0000-0000-000000000203', 'agent-sub-b', 'loan',     'active', DATE '2026-03-01',   0.00, 'SGD', 'SG-001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL),
    ('00000000-0000-0000-0000-000000000234', '00000000-0000-0000-0000-000000000203', 'agent-sub-a', 'business', 'active', DATE '2026-04-01',   0.00, 'SGD', 'SG-001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000241', '00000000-0000-0000-0000-000000000204', 'agent-sub-a', 'savings',  'active', DATE '2026-01-01',  50.00, 'SGD', 'SG-001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL);
