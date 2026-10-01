DELETE FROM accounts WHERE client_id = '00000000-0000-0000-0000-000000000201';
DELETE FROM clients WHERE client_id = '00000000-0000-0000-0000-000000000201';
INSERT INTO clients (
    client_id, first_name, last_name, date_of_birth, gender, email, phone_number,
    address, city, state, country, postal_code, verification_status,
    created_by_agent_id, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000201', 'Casey', 'Client', '1990-05-10', 'female',
    'casey-201@example.test', '+6560000201', '1 Example Street', 'Singapore', 'Singapore',
    'Singapore', '123456', 'UNVERIFIED', '00000000-0000-0000-0000-000000000901',
    '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'
);
