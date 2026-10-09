CREATE TABLE users (
    id           UUID PRIMARY KEY,
    first_name   VARCHAR(50)  NOT NULL,
    last_name    VARCHAR(50)  NOT NULL,
    email        VARCHAR(255) NOT NULL,
    role         VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'AGENT')),
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    deletable    BOOLEAN      NOT NULL DEFAULT TRUE,
    idp_sub      VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_idp_sub UNIQUE (idp_sub)
);

-- Audit events waiting to be sent to the auditor's SQS queue (ADR-0020). A row
-- is written in its own transaction right after the request it describes, and
-- deleted once AuditOutboxRelay has sent it. event_id is the auditor's
-- de-duplication key, so a row sent twice is stored only once.
CREATE TABLE audit_outbox (
    event_id    UUID        PRIMARY KEY,
    body        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_outbox_created_at ON audit_outbox (created_at);
