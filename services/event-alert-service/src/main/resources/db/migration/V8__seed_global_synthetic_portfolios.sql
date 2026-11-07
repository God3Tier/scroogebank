INSERT INTO instrument (id, ticker, issuer_name, sector, currency, active)
VALUES
  ('SGX-O39', 'O39', 'Oversea-Chinese Banking Corporation Ltd', 'Financials', 'SGD', TRUE),
  ('SGX-Z74', 'Z74', 'Singapore Telecommunications Ltd', 'Communication Services', 'SGD', TRUE),
  ('SGX-C6L', 'C6L', 'Singapore Airlines Ltd', 'Industrials', 'SGD', TRUE),
  ('NASDAQ-AAPL', 'AAPL', 'Apple Inc', 'Technology', 'USD', TRUE),
  ('NASDAQ-MSFT', 'MSFT', 'Microsoft Corporation', 'Technology', 'USD', TRUE),
  ('NASDAQ-NVDA', 'NVDA', 'NVIDIA Corporation', 'Technology', 'USD', TRUE),
  ('NASDAQ-GOOGL', 'GOOGL', 'Alphabet Inc', 'Communication Services', 'USD', TRUE),
  ('NYSE-JPM', 'JPM', 'JPMorgan Chase & Co', 'Financials', 'USD', TRUE),
  ('NYSE-V', 'V', 'Visa Inc', 'Financials', 'USD', TRUE),
  ('NYSE-KO', 'KO', 'The Coca-Cola Company', 'Consumer Staples', 'USD', TRUE),
  ('NYSE-DIS', 'DIS', 'The Walt Disney Company', 'Communication Services', 'USD', TRUE),
  ('LSE-HSBA', 'HSBA', 'HSBC Holdings plc', 'Financials', 'GBP', TRUE),
  ('LSE-SHEL', 'SHEL', 'Shell plc', 'Energy', 'GBP', TRUE),
  ('HKEX-0700', '0700', 'Tencent Holdings Ltd', 'Communication Services', 'HKD', TRUE),
  ('HKEX-9988', '9988', 'Alibaba Group Holding Ltd', 'Consumer Discretionary', 'HKD', TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO synthetic_portfolio (client_ref, account_ref, assigned_agent_subject, source_version)
SELECT
  'syn-client-' || to_char(client_number, 'FM000'),
  'syn-account-' || to_char(client_number, 'FM000'),
  'syn-agent-' || to_char(((client_number - 1) % 4) + 1, 'FM000'),
  'global-synthetic-holdings-v1'
FROM generate_series(1, 20) AS clients(client_number)
ON CONFLICT (client_ref) DO UPDATE SET
  account_ref = EXCLUDED.account_ref,
  assigned_agent_subject = EXCLUDED.assigned_agent_subject,
  source_version = EXCLUDED.source_version;
