CREATE TABLE instrument_search_coverage (
    instrument_id VARCHAR(32) PRIMARY KEY REFERENCES instrument(id),
    last_successful_to TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
