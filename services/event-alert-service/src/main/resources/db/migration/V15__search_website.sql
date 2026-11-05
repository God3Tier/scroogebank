-- One shared list of websites that is searched for every company (ADR-0082, superseding the per-company
-- sites of ADR-0079). Agents add them; an agent or admin can remove one. Removing keeps the row.
CREATE TABLE search_website (
    id UUID PRIMARY KEY,
    domain VARCHAR(253) NOT NULL,
    added_by_subject VARCHAR(128) NOT NULL,
    added_at TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    deactivated_by_subject VARCHAR(128),
    deactivated_at TIMESTAMPTZ,
    CONSTRAINT ck_search_website_lowercase CHECK (domain = lower(domain)),
    CONSTRAINT ck_search_website_deactivation CHECK (
        (active AND deactivated_at IS NULL AND deactivated_by_subject IS NULL)
        OR (NOT active AND deactivated_at IS NOT NULL AND deactivated_by_subject IS NOT NULL))
);

-- One active entry per website; removed rows stay as history.
CREATE UNIQUE INDEX uq_search_website_active ON search_website (domain) WHERE active;

-- Carry over what agents already added per company: each distinct website, once, by whoever added it first.
-- instrument_source_domain stays in place as history and is no longer read.
INSERT INTO search_website (id, domain, added_by_subject, added_at)
SELECT DISTINCT ON (domain) id, domain, added_by_subject, added_at
  FROM instrument_source_domain
 WHERE active
 ORDER BY domain, added_at;
