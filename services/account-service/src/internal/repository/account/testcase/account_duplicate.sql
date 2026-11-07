-- A client who already holds a checking account that is not deleted. One
-- account per type per client (uk_accounts_client_type), so a second checking
-- account is refused.
DELETE FROM accounts WHERE client_id = '00000000-0000-0000-0000-000000000201';
INSERT INTO accounts (id, client_id, owner_agent_id, account_type, status, opening_date, initial_deposit, currency, branch_id, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000208', '00000000-0000-0000-0000-000000000201', 'agent-sub-901', 'checking', 'active', CURRENT_DATE, 0.00, 'SGD', 'SG-001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
