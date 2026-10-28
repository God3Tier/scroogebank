-- Synthetic-only MVP reference data. Holdings carry only this stable public ID;
-- Singapore resolves it to public search terms before any provider request.
INSERT INTO instrument (id, ticker, issuer_name, sector, currency, active)
VALUES ('SYN-SGX-001', 'SYN1', 'Synthetic Issuer One', 'Synthetic', 'SGD', TRUE)
ON CONFLICT (id) DO NOTHING;
