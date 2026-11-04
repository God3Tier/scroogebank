-- Search domains that agents add for a company (ADR-0079). Official domains stay on
-- instrument.official_domains; these are extra, per company, and can be deactivated.
CREATE TABLE instrument_source_domain (
    id UUID PRIMARY KEY,
    instrument_id VARCHAR(32) NOT NULL REFERENCES instrument(id),
    domain VARCHAR(253) NOT NULL,
    added_by_subject VARCHAR(128) NOT NULL,
    added_at TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    deactivated_by_subject VARCHAR(128),
    deactivated_at TIMESTAMPTZ,
    CONSTRAINT ck_source_domain_lowercase CHECK (domain = lower(domain)),
    CONSTRAINT ck_source_domain_deactivation CHECK (
        (active AND deactivated_at IS NULL AND deactivated_by_subject IS NULL)
        OR (NOT active AND deactivated_at IS NOT NULL AND deactivated_by_subject IS NOT NULL))
);

-- One active entry per company and domain; deactivated rows stay as history.
CREATE UNIQUE INDEX uq_source_domain_active
    ON instrument_source_domain (instrument_id, domain) WHERE active;

CREATE INDEX ix_source_domain_instrument ON instrument_source_domain (instrument_id) WHERE active;
