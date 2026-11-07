-- engagement-db (ADR-0051). Each table belongs to one module, named in its
-- comment; no module reads another's table, and no other service reads this
-- database. Times are UTC.

-- calendar: an agent's events. client_id points at client-service's client
-- and is checked there with the agent's token; it is not a foreign key.
CREATE TABLE events (
    id              UUID PRIMARY KEY,
    owner_agent_sub VARCHAR(64)   NOT NULL,
    client_id       UUID,
    title           VARCHAR(120)  NOT NULL,
    description     VARCHAR(1000),
    location        VARCHAR(200),
    starts_at       TIMESTAMPTZ   NOT NULL,
    ends_at         TIMESTAMPTZ   NOT NULL,
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    ics_sequence    INTEGER       NOT NULL DEFAULT 0 CHECK (ics_sequence >= 0),
    -- Raised by every update and the cancel; mail rows record it.
    revision        INTEGER       NOT NULL DEFAULT 0 CHECK (revision >= 0),
    version         INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CHECK (ends_at > starts_at)
);

CREATE INDEX idx_events_owner_starts ON events (owner_agent_sub, starts_at);
CREATE INDEX idx_events_owner_client ON events (owner_agent_sub, client_id, starts_at);

-- notifications: invites, updates, cancellations and reminders to the agent.
-- One row per message. event_version is the event's revision when the row
-- was queued, so a rescheduled reminder never collides with the one it
-- replaced.
-- offset_minutes is 0 for invite rows.
CREATE TABLE mail_queue (
    id              UUID PRIMARY KEY,
    kind            VARCHAR(20)   NOT NULL CHECK (kind IN ('INVITE', 'UPDATE', 'CANCEL', 'REMINDER')),
    event_id        UUID          NOT NULL,
    owner_agent_sub VARCHAR(64)   NOT NULL,
    event_version   INTEGER       NOT NULL,
    offset_minutes  INTEGER       NOT NULL DEFAULT 0 CHECK (offset_minutes >= 0),
    ics_sequence    INTEGER       NOT NULL DEFAULT 0,
    due_at          TIMESTAMPTZ   NOT NULL,
    status          VARCHAR(20)   NOT NULL
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'MISSED', 'CANCELLED', 'DISABLED')),
    attempts        INTEGER       NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ,
    leased_until    TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    ses_message_id  VARCHAR(200),
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    UNIQUE (event_id, kind, offset_minutes, event_version)
);

-- What the dispatcher claims (ADR-0051 decision 4).
CREATE INDEX idx_mail_queue_pending ON mail_queue (due_at) WHERE status = 'PENDING';
CREATE INDEX idx_mail_queue_event ON mail_queue (event_id, kind);

-- notifications: the agent's choices. No row means the defaults (both on).
CREATE TABLE preferences (
    agent_sub         VARCHAR(64) PRIMARY KEY,
    invites_enabled   BOOLEAN     NOT NULL,
    reminders_enabled BOOLEAN     NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);

-- holidays: public holidays from official lists (ADR-0054 decision 8). Rows
-- are added by migrations, never edited.
CREATE TABLE holidays (
    id              UUID PRIMARY KEY,
    country_code    VARCHAR(2)   NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    holiday_date    DATE         NOT NULL,
    name            VARCHAR(100) NOT NULL,
    observed        BOOLEAN      NOT NULL DEFAULT FALSE,
    source          VARCHAR(300) NOT NULL,
    dataset_version VARCHAR(50)  NOT NULL,
    UNIQUE (country_code, holiday_date, name)
);

CREATE INDEX idx_holidays_country_date ON holidays (country_code, holiday_date);

-- drafting: follow-up drafts. Subject and body hold placeholders, never the
-- client's name or address (ADR-0054 decision 6), and are cleared when the
-- draft is sent or discarded (ADR-0068 decision 4). SENDING is internal: it
-- holds a draft while one send is in flight, so it is sent at most once.
-- health_task_id and alert_id are what a follow-up came from (ADR-0068).
CREATE TABLE email_drafts (
    id               UUID PRIMARY KEY,
    owner_agent_sub  VARCHAR(64)   NOT NULL,
    client_id        UUID          NOT NULL,
    event_id         UUID,
    health_task_id   VARCHAR(128),
    alert_id         UUID,
    purpose          VARCHAR(30)   NOT NULL
        CHECK (purpose IN ('CHECK_IN', 'MEETING_FOLLOW_UP', 'HOLIDAY_GREETING', 'PRE_HOLIDAY_REVIEW',
                           'HEALTH_CHECK_IN', 'ALERT_FOLLOW_UP')),
    tone             VARCHAR(10)   NOT NULL CHECK (tone IN ('FORMAL', 'WARM')),
    subject_template VARCHAR(120),
    body_template    VARCHAR(5000),
    edited           BOOLEAN       NOT NULL DEFAULT false,
    source           VARCHAR(10)   NOT NULL CHECK (source IN ('AI', 'TEMPLATE')),
    source_reason    VARCHAR(40),
    model_id         VARCHAR(100),
    prompt_version   VARCHAR(40),
    holiday_id       UUID,
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT', 'SENDING', 'SENT', 'DISCARDED')),
    ses_message_id   VARCHAR(200),
    sent_at          TIMESTAMPTZ,
    version          INTEGER       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    CHECK (status IN ('SENT', 'DISCARDED') OR (subject_template IS NOT NULL AND body_template IS NOT NULL))
);

CREATE INDEX idx_email_drafts_owner_created ON email_drafts (owner_agent_sub, created_at DESC);

-- drafting: model use for the caps (ADR-0054 decision 7). usage_date is the
-- Singapore date.
CREATE TABLE ai_usage (
    usage_date    DATE        NOT NULL,
    agent_sub     VARCHAR(64) NOT NULL,
    drafts        INTEGER     NOT NULL DEFAULT 0,
    input_tokens  BIGINT      NOT NULL DEFAULT 0,
    output_tokens BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (usage_date, agent_sub)
);

-- audit: the transactional outbox, as event-alert-service's (ADR-0020).
CREATE TABLE audit_outbox (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    event_version   INTEGER      NOT NULL CHECK (event_version > 0),
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'LEASED', 'DELIVERED', 'FAILED')),
    attempt_count   INTEGER      NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_until     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    acknowledged_at TIMESTAMPTZ
);

CREATE INDEX idx_audit_outbox_pending ON audit_outbox (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');
