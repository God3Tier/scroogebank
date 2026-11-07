ALTER TABLE instrument
  ADD COLUMN search_aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN official_domains JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD CONSTRAINT ck_instrument_search_aliases_array
    CHECK (jsonb_typeof(search_aliases) = 'array'),
  ADD CONSTRAINT ck_instrument_official_domains_array
    CHECK (jsonb_typeof(official_domains) = 'array');

UPDATE instrument AS instrument
SET search_aliases = metadata.aliases,
    official_domains = metadata.domains
FROM (VALUES
  ('SYN-SGX-001', '["Synthetic Issuer One"]'::jsonb, '[]'::jsonb),
  ('SGX-D05', '["DBS", "DBS Group"]'::jsonb, '["sgx.com", "mas.gov.sg", "dbs.com"]'::jsonb),
  ('SGX-O39', '["OCBC", "OCBC Bank"]'::jsonb, '["sgx.com", "mas.gov.sg", "ocbc.com"]'::jsonb),
  ('SGX-Z74', '["Singtel"]'::jsonb, '["sgx.com", "singtel.com"]'::jsonb),
  ('SGX-C6L', '["Singapore Airlines", "SIA"]'::jsonb, '["sgx.com", "singaporeair.com"]'::jsonb),
  ('NASDAQ-AAPL', '["Apple"]'::jsonb, '["sec.gov", "nasdaq.com", "apple.com"]'::jsonb),
  ('NASDAQ-MSFT', '["Microsoft"]'::jsonb, '["sec.gov", "nasdaq.com", "microsoft.com"]'::jsonb),
  ('NASDAQ-NVDA', '["NVIDIA"]'::jsonb, '["sec.gov", "nasdaq.com", "nvidia.com"]'::jsonb),
  ('NASDAQ-GOOGL', '["Alphabet", "Google"]'::jsonb, '["sec.gov", "nasdaq.com", "abc.xyz"]'::jsonb),
  ('NYSE-JPM', '["JPMorgan", "JPMorgan Chase"]'::jsonb, '["sec.gov", "nyse.com", "jpmorganchase.com"]'::jsonb),
  ('NYSE-V', '["Visa"]'::jsonb, '["sec.gov", "nyse.com", "visa.com"]'::jsonb),
  ('NYSE-KO', '["Coca-Cola", "Coca Cola"]'::jsonb, '["sec.gov", "nyse.com", "coca-colacompany.com"]'::jsonb),
  ('NYSE-DIS', '["Disney", "Walt Disney"]'::jsonb, '["sec.gov", "nyse.com", "thewaltdisneycompany.com"]'::jsonb),
  ('LSE-HSBA', '["HSBC"]'::jsonb, '["londonstockexchange.com", "hsbc.com"]'::jsonb),
  ('LSE-SHEL', '["Shell"]'::jsonb, '["londonstockexchange.com", "shell.com"]'::jsonb),
  ('HKEX-0700', '["Tencent"]'::jsonb, '["hkexnews.hk", "tencent.com"]'::jsonb),
  ('HKEX-9988', '["Alibaba", "Alibaba Group"]'::jsonb, '["hkexnews.hk", "alibabagroup.com"]'::jsonb)
) AS metadata(id, aliases, domains)
WHERE instrument.id = metadata.id;
