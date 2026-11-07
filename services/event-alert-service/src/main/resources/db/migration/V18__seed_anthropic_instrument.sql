-- Anthropic, so the demo can hold and search it in every environment. Teardown empties alerts-db (ADR-0058) and a migration runs
-- again on every fresh database, so the company is always there. It is a private company: it has no listed ticker, so the ticker
-- field is the name news is searched by, and it has no official domains or nicknames. Safe to run twice.
INSERT INTO instrument (id, ticker, issuer_name, sector, currency, active, search_aliases, official_domains)
SELECT 'PRIV-ANTHROPIC', 'Anthropic', 'Anthropic', 'Technology', 'USD', true, '[]'::jsonb, '[]'::jsonb
 WHERE NOT EXISTS (SELECT 1 FROM instrument WHERE id = 'PRIV-ANTHROPIC');
