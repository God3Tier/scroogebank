-- Clients
CREATE TABLE clients (
    client_id             UUID PRIMARY KEY,
    first_name            VARCHAR(50)  NOT NULL,
    last_name             VARCHAR(50)  NOT NULL,
    date_of_birth         DATE         NOT NULL,
    gender                VARCHAR(20)  NOT NULL,
    email                 VARCHAR(255) NOT NULL,
    phone_number          VARCHAR(20)  NOT NULL,
    address               VARCHAR(100) NOT NULL,
    city                  VARCHAR(50)  NOT NULL,
    state                 VARCHAR(50)  NOT NULL,
    country               VARCHAR(50)  NOT NULL,
    postal_code           VARCHAR(10)  NOT NULL,
    verification_status   VARCHAR(20)  NOT NULL DEFAULT 'UNVERIFIED',
    created_by_agent_id   VARCHAR(128) NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    -- Set on delete; the row is kept but every API treats it as missing (ADR-0036).
    deleted_at            TIMESTAMPTZ
);

-- Unique among clients that are not deleted, so a deleted client's details can be reused.
CREATE UNIQUE INDEX uk_clients_email ON clients (email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_clients_phone ON clients (phone_number) WHERE deleted_at IS NULL;
CREATE INDEX idx_clients_created_by_agent_id ON clients (created_by_agent_id);
CREATE INDEX idx_clients_updated_at ON clients (updated_at DESC);
-- The business dashboard counts clients by creation date (ADR-0038).
CREATE INDEX idx_clients_created_at ON clients (created_at DESC);

-- Accounts moved to account-service, which owns them in its own database
-- (services/account-service/data/V1_init.sql, ADR-0017, ADR-0035). The old
-- accounts-to-clients foreign key is replaced by API checks in both services.

-- Audit events waiting to be sent to the Auditor's SQS queue (ADR-0020). The
-- audit middleware writes a row in its own transaction right after each
-- request, and the relay deletes it once SQS has accepted it. event_id is the
-- Auditor's de-duplication key, so a row sent twice is stored only once.
CREATE TABLE audit_outbox (
    event_id    UUID        PRIMARY KEY,
    body        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_outbox_created_at ON audit_outbox (created_at);
