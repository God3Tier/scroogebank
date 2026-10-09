-- Accounts for the dashboard aggregates. Own owners and dates in 2031, so rows
-- from other packages' fixtures never land in these owners or these windows.
DELETE FROM accounts WHERE id::text LIKE '00000000-0000-0000-0000-0000000006%';
INSERT INTO accounts (
    id, client_id, owner_agent_id, account_type, status, opening_date,
    initial_deposit, currency, branch_id, deleted_at
) VALUES
    -- dash-owner-a: two opened in September, one the day before, one deleted.
    ('00000000-0000-0000-0000-000000000601', '00000000-0000-0000-0000-000000000611', 'dash-owner-a', 'savings', 'active', '2031-09-01', 100.00, 'SGD', 'B1', NULL),
    ('00000000-0000-0000-0000-000000000602', '00000000-0000-0000-0000-000000000611', 'dash-owner-a', 'checking', 'active', '2031-09-15', 250.50, 'SGD', 'B1', NULL),
    ('00000000-0000-0000-0000-000000000603', '00000000-0000-0000-0000-000000000612', 'dash-owner-a', 'savings', 'closed', '2031-08-31', 1000.00, 'SGD', 'B2', NULL),
    ('00000000-0000-0000-0000-000000000604', '00000000-0000-0000-0000-000000000612', 'dash-owner-a', 'checking', 'active', '2031-09-10', 999.00, 'SGD', 'B2', '2031-09-11 09:00:00+08'),
    -- dash-owner-b: opened on the window's last day.
    ('00000000-0000-0000-0000-000000000605', '00000000-0000-0000-0000-000000000613', 'dash-owner-b', 'savings', 'active', '2031-09-30', 10.00, 'SGD', 'B1', NULL),
    -- dash-owner-c: two currencies in November.
    ('00000000-0000-0000-0000-000000000606', '00000000-0000-0000-0000-000000000614', 'dash-owner-c', 'savings', 'active', '2031-11-15', 5.00, 'USD', 'B3', NULL),
    ('00000000-0000-0000-0000-000000000607', '00000000-0000-0000-0000-000000000614', 'dash-owner-c', 'checking', 'active', '2031-11-20', 7.00, 'SGD', 'B3', NULL);
