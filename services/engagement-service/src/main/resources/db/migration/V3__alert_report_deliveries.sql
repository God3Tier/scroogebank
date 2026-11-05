-- alertreports: event reports emailed to chosen clients after the assigned agent approves them (ADR-0084).
-- A batch is the agent's one approval: an immutable snapshot of the report as approved, plus the agent's
-- optional note. A delivery is one client's email. Client addresses are never stored: they are read from
-- client-service for the message and dropped. The snapshot and note are cleared once nothing is left to
-- send; the hashes, IDs and statuses stay as evidence.
CREATE TABLE alert_report_batch (
    id                 UUID PRIMARY KEY,
    owner_agent_sub    VARCHAR(64)  NOT NULL,
    idempotency_key    VARCHAR(128) NOT NULL,
    request_hash       CHAR(64)     NOT NULL,
    event_id           UUID         NOT NULL,
    report_id          UUID         NOT NULL,
    report_version     INTEGER      NOT NULL CHECK (report_version >= 1),
    report_hash        CHAR(64)     NOT NULL,
    snapshot           JSONB,
    agent_note         VARCHAR(500),
    note_hash          CHAR(64),
    created_at         TIMESTAMPTZ  NOT NULL,
    content_cleared_at TIMESTAMPTZ,
    UNIQUE (owner_agent_sub, idempotency_key)
);

CREATE TABLE alert_report_delivery (
    id              UUID PRIMARY KEY,
    batch_id        UUID         NOT NULL REFERENCES alert_report_batch (id),
    report_id       UUID         NOT NULL,
    alert_id        UUID         NOT NULL,
    position        INTEGER      NOT NULL,
    client_ref      VARCHAR(64),
    instrument_id   VARCHAR(32),
    issuer_name     VARCHAR(200),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'NOT_AUTHORISED')),
    attempts        INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    leased_until    TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    ses_message_id  VARCHAR(200),
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (batch_id, alert_id)
);

-- A report version reaches a client at most once: a second batch cannot queue or resend it.
CREATE UNIQUE INDEX uq_alert_report_delivery_active
    ON alert_report_delivery (report_id, alert_id) WHERE status IN ('QUEUED', 'SENT');
CREATE INDEX idx_alert_report_delivery_queued ON alert_report_delivery (batch_id) WHERE status = 'QUEUED';
