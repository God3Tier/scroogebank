-- News alerts sent from Follow-up Emails (docs/feature-plans/event-driven-alerts-news-alert-drafts_plan.md).
--
-- 1. "Continue monitoring" is gone: open MONITORING alerts become NEW, and SENT_OUT is the new final status, set only
--    when engagement-service has emailed the client. Old alert_decision and agent_event_action rows that say MONITOR or
--    MONITORING are kept as history and stay readable.
UPDATE alert SET status = 'NEW', version = version + 1 WHERE status = 'MONITORING';

ALTER TABLE alert DROP CONSTRAINT alert_status_check;
ALTER TABLE alert ADD CONSTRAINT alert_status_check
    CHECK (status IN ('NEW', 'INFORMED', 'DISMISSED', 'SENT_OUT'));

-- 2. One sentence per report version, written by the worker straight after the report, from that report's public text
--    only. Null when the sentence is switched off or failed its checks; the email then uses the report's first sentence.
ALTER TABLE event_report ADD COLUMN summary_sentence VARCHAR(250);
ALTER TABLE event_report ADD COLUMN summary_model VARCHAR(100);

-- 3. An agent's unapproved edit of one report version: the edited sections only. Edits belong to that agent; other
--    agents holding the ticker still see the original.
CREATE TABLE report_edit (
    event_report_id UUID NOT NULL REFERENCES event_report(id),
    agent_sub VARCHAR(128) NOT NULL,
    version INTEGER NOT NULL CHECK (version >= 1),
    sections JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_report_id, agent_sub)
);

-- 4. An agent's approval of one report version: the frozen text the client receives. Approval is final. final_text is
--    cleared when every client on it is Sent Out, or 14 days after valid_until; the hashes stay.
CREATE TABLE approved_report (
    id UUID PRIMARY KEY,
    event_report_id UUID NOT NULL REFERENCES event_report(id),
    public_event_id UUID NOT NULL REFERENCES public_event(id),
    version INTEGER NOT NULL CHECK (version >= 1),
    agent_sub VARCHAR(128) NOT NULL,
    final_text JSONB,
    edited BOOLEAN NOT NULL,
    original_hash CHAR(64) NOT NULL,
    final_hash CHAR(64) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    text_cleared_at TIMESTAMPTZ,
    CONSTRAINT uk_approved_report_agent UNIQUE (event_report_id, agent_sub),
    CHECK (final_text IS NOT NULL OR text_cleared_at IS NOT NULL)
);

CREATE INDEX idx_approved_report_event_agent ON approved_report(public_event_id, agent_sub);

-- 5. Which email took a client to Sent Out, for the Sent out view and the audit trail.
CREATE TABLE alert_sent_out (
    alert_id UUID PRIMARY KEY REFERENCES alert(id),
    approval_id UUID REFERENCES approved_report(id),
    draft_id UUID NOT NULL,
    ses_message_id VARCHAR(200) NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    recorded_by VARCHAR(128) NOT NULL
);

CREATE INDEX idx_alert_sent_out_sent ON alert_sent_out(sent_at DESC);
