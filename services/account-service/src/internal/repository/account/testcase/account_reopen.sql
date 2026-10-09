-- A client whose checking account was deleted. Only accounts that are not
-- deleted count towards one account per type (ADR-0036), so a new checking
-- account can be opened.
DELETE FROM accounts WHERE client_id = '00000000-0000-0000-0000-000000000201';
INSERT INTO accounts (id, client_id, owner_agent_id, account_type, status, opening_date, initial_deposit, currency, branch_id, created_at, updated_at, deleted_at)
VALUES ('00000000-0000-0000-0000-000000000207', '00000000-0000-0000-0000-000000000201', 'agent-sub-901', 'checking', 'active', CURRENT_DATE, 0.00, 'SGD', 'SG-001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
