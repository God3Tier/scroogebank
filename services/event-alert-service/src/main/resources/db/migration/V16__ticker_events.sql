-- One report per ticker per search run (ADR-0084, as amended). An event is now one ticker's batch of new articles
-- rather than one story, so it records the ticker it belongs to, and one article may lead the events of several
-- tickers it names.
--
-- Old events keep a NULL instrument_id and keep working: they are read through public_event_instrument as before.
ALTER TABLE public_event ADD COLUMN instrument_id VARCHAR(32) REFERENCES instrument(id);

CREATE INDEX idx_public_event_instrument_created ON public_event(instrument_id, created_at DESC);

-- The old rule allowed one event per source and classifier version. One source can now be the first source of
-- the events of two tickers, so the ticker joins the key.
ALTER TABLE public_event DROP CONSTRAINT uk_public_event_source_classifier;

CREATE UNIQUE INDEX uk_public_event_source_classifier_instrument
    ON public_event (source_result_id, classifier_version, COALESCE(instrument_id, ''));
