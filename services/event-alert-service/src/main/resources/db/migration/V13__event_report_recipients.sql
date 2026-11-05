-- The agent approval of one report version for chosen clients, and the delivery state of each (ADR-0084).
-- A row exists only after the assigned agent approved that report for that client alert. SENT is final:
-- a recipient is never emailed twice for the same report version.
CREATE TABLE event_report_recipient (
    report_id UUID NOT NULL REFERENCES event_report(id),
    alert_id UUID NOT NULL REFERENCES alert(id),
    agent_subject VARCHAR(128) NOT NULL,
    client_ref VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('APPROVED', 'SENDING', 'SENT')),
    approved_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    message_id VARCHAR(256),
    PRIMARY KEY (report_id, alert_id)
);
CREATE INDEX idx_event_report_recipient_agent ON event_report_recipient (agent_subject, status);
