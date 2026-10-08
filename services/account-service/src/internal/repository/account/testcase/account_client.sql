-- Accounts have no foreign key to clients (they live in separate databases),
-- so creating an account needs no client row here. Clear earlier runs only.
DELETE FROM accounts WHERE client_id = '00000000-0000-0000-0000-000000000201';
