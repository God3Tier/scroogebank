-- One row per "search now" request made when an agent adds a search domain (ADR-0080).
-- It is the record the cooldown and the daily cap count, and shows who asked for what.
CREATE TABLE source_search_request (
    id UUID PRIMARY KEY,
    instrument_id VARCHAR(32) NOT NULL REFERENCES instrument(id),
    domain VARCHAR(253) NOT NULL,
    requested_by_subject VARCHAR(128) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL,
    detail VARCHAR(200),
    CONSTRAINT ck_source_search_status CHECK (
        status IN ('STARTED', 'COOLDOWN', 'DAILY_LIMIT', 'FAILED'))
);

CREATE INDEX ix_source_search_request_instrument
    ON source_search_request (instrument_id, requested_at DESC) WHERE status = 'STARTED';

CREATE INDEX ix_source_search_request_time
    ON source_search_request (requested_at) WHERE status = 'STARTED';
