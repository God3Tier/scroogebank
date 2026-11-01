-- synthetic_portfolio was never seeded for any client, so holding_snapshot's
-- foreign key to it rejected every synthetic position (ADR-0032's fixtures
-- assume these rows exist). Add the two synthetic clients the holdings
-- fixture already uses.
INSERT INTO synthetic_portfolio (client_ref, account_ref, assigned_agent_subject, source_version)
VALUES
  ('syn-client-001', 'syn-account-001', 'syn-agent-001', 'synthetic-holdings-v1'),
  ('syn-client-002', 'syn-account-002', 'syn-agent-002', 'synthetic-holdings-v1')
ON CONFLICT (client_ref) DO NOTHING;
