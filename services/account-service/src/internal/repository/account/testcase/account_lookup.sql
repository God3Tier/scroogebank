DELETE FROM accounts WHERE id = '00000000-0000-0000-0000-000000000202';
DELETE FROM accounts WHERE client_id = '00000000-0000-0000-0000-000000000202';
INSERT INTO accounts (id, client_id, owner_agent_id, account_type, status, opening_date, initial_deposit, currency, branch_id, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000901', 'savings', 'active', CURRENT_DATE, 100.00, 'SGD', 'SG-001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
