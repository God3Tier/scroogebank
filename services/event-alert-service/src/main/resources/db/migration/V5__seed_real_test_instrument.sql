-- Adds one real, SGX-listed instrument alongside the fully synthetic
-- SYN-SGX-001 catalogue entry. Searching a real public company lets the
-- The live search provider can find real public news during a manual
-- testing; a purely synthetic name ("Synthetic Issuer One") can never match
-- anything real. DBS Group Holdings was already the example query in the
-- search spike's own README, so it's used here for consistency.
--
-- This is still demonstration/test data only (ADR-0005): no real holdings or
-- client data is implied, only that this ticker is a real, searchable company.
INSERT INTO instrument (id, ticker, issuer_name, sector, currency, active)
VALUES ('SGX-D05', 'D05', 'DBS Group Holdings Ltd', 'Financials', 'SGD', TRUE)
ON CONFLICT (id) DO NOTHING;
