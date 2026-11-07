-- Several sources about one company event become one event with one versioned report (ADR-0084).
--
-- public_event_source links every source to the event it supports. public_event.source_result_id stays
-- as the first source, so nothing that reads it changes.
CREATE TABLE public_event_source (
    public_event_id UUID NOT NULL REFERENCES public_event(id),
    source_result_id UUID NOT NULL REFERENCES source_result(id),
    linked_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (public_event_id, source_result_id)
);

CREATE INDEX idx_public_event_source_result ON public_event_source(source_result_id);

-- Every existing event is linked to the source it came from.
INSERT INTO public_event_source (public_event_id, source_result_id, linked_at)
SELECT id, source_result_id, created_at FROM public_event;

-- One report per event and version, stored once rather than once per client alert. The report is the model's
-- validated output (schema alert-client-report-v2); the fixed text the application adds is not stored here.
CREATE TABLE event_report (
    id UUID PRIMARY KEY,
    public_event_id UUID NOT NULL REFERENCES public_event(id),
    version INTEGER NOT NULL CHECK (version >= 1),
    schema_version VARCHAR(40) NOT NULL,
    report JSONB NOT NULL,
    source_ids JSONB NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    ai_generated BOOLEAN NOT NULL CHECK (ai_generated),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_event_report_version UNIQUE (public_event_id, version)
);

CREATE INDEX idx_event_report_event ON event_report(public_event_id, version DESC);

-- Existing briefings become version 1 of their event's report, from the earliest briefing of each event. The old
-- briefing table is kept untouched so a rollback still works.
INSERT INTO event_report (id, public_event_id, version, schema_version, report, source_ids,
                          model_version, prompt_version, ai_generated, created_at, expires_at)
SELECT DISTINCT ON (a.public_event_id)
       gen_random_uuid(), a.public_event_id, 1, 'briefing-v1-legacy',
       jsonb_build_object(
           'headline', sr.title,
           'executiveSummary', jsonb_build_array(jsonb_build_object('text', b.summary, 'sourceRefs', b.citation_refs)),
           'keyFacts', '[]'::jsonb,
           'whyItMatters', '[]'::jsonb,
           'marketContext', NULL,
           'whatToMonitor', '[]'::jsonb,
           'uncertainty', b.uncertainty,
           'sourceRefs', b.citation_refs),
       jsonb_build_array(sr.id::text), b.model_version, b.prompt_version, TRUE, b.created_at, b.expires_at
  FROM briefing b
  JOIN alert a ON a.id = b.alert_id
  JOIN public_event pe ON pe.id = a.public_event_id
  JOIN source_result sr ON sr.id = pe.source_result_id
 ORDER BY a.public_event_id, b.created_at ASC;

-- How many times a report was tried for an event without success, so a source the model keeps refusing is not
-- paid for on every run.
CREATE TABLE event_report_attempt (
    public_event_id UUID PRIMARY KEY REFERENCES public_event(id),
    attempts INTEGER NOT NULL CHECK (attempts >= 0),
    last_reason VARCHAR(64),
    last_attempt_at TIMESTAMPTZ NOT NULL
);
