-- Source-to-alert pipeline (ADR-0049). The minimum public excerpt ADR-0011 allows
-- is kept with the source so a worker run can classify it later and a replay can
-- repeat that. It expires with the row (90 days) and is redacted by retention.
-- processed_at stays NULL until the pipeline reaches a definitive outcome, so a
-- source the model could not yet classify is tried again on the next run.
ALTER TABLE source_result
    ADD COLUMN excerpt VARCHAR(1000),
    ADD COLUMN processed_at TIMESTAMPTZ,
    ADD COLUMN processing_outcome VARCHAR(40);

CREATE INDEX idx_source_result_unprocessed ON source_result(retrieved_at)
    WHERE processed_at IS NULL;
