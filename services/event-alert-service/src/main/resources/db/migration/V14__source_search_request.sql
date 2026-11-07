-- One row per company searched because an agent added a website or pressed Search now (ADR-0081).
-- It is the record the cooldown counts, and shows who asked for what.
CREATE TABLE source_search_request (
    id UUID PRIMARY KEY,
    instrument_id VARCHAR(32) NOT NULL REFERENCES instrument(id),
    domain VARCHAR(253),
    kind VARCHAR(8) NOT NULL DEFAULT 'ADD',
    requested_by_subject VARCHAR(128) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL,
    detail VARCHAR(200),
    CONSTRAINT ck_source_search_kind CHECK (kind IN ('ADD', 'MANUAL')),
    CONSTRAINT ck_source_search_status CHECK (
        status IN ('STARTED', 'COOLDOWN', 'DAILY_LIMIT', 'FAILED'))
);

CREATE INDEX ix_source_search_request_instrument
    ON source_search_request (instrument_id, requested_at DESC) WHERE status = 'STARTED';

CREATE INDEX ix_source_search_request_time
    ON source_search_request (requested_at) WHERE status = 'STARTED';
