DELETE FROM accounts WHERE id = '00000000-0000-0000-0000-000000000202';
DELETE FROM accounts WHERE client_id = '00000000-0000-0000-0000-000000000202';
DELETE FROM clients WHERE client_id = '00000000-0000-0000-0000-000000000202';
INSERT INTO clients (
    client_id, first_name, last_name, date_of_birth, gender, email, phone_number,
    address, city, state, country, postal_code, verification_status,
    created_by_agent_id, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000202', 'Jordan', 'Client', '1990-05-10', 'female',
    'jordan-202@example.test', '+6560000202', '2 Example Street', 'Singapore', 'Singapore',
    'Singapore', '123456', 'UNVERIFIED', '00000000-0000-0000-0000-000000000901',
    '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'
);
INSERT INTO accounts (id, client_id, account_type, status, opening_date, initial_deposit, currency, branch_id, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000202', 'savings', 'active', CURRENT_DATE, 100.00, 'SGD', 'SG-001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
