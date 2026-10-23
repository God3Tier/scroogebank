-- Accounts. Owned by account-service; split out of
-- client-profile-management-service (ADR-0017, ADR-0035).
--
-- There is no foreign key to clients: clients live in another service's
-- database. account-service checks the client through
-- client-profile-management-service when an account is created, and copies
-- the client's owning agent into owner_agent_id so ownership checks stay local.
CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    client_id       UUID           NOT NULL,
    -- Cognito sub of the agent who owns client_id, copied at creation and
    -- updated when an admin reassigns the client (ADR-0060).
    -- VARCHAR(128) to match clients.assigned_agent_id: Cognito does not
    -- guarantee that sub is a UUID.
    owner_agent_id  VARCHAR(128)   NOT NULL,
    account_type    VARCHAR(20)    NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'active',
    opening_date    DATE           NOT NULL DEFAULT CURRENT_DATE,
    initial_deposit NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency        CHAR(3)        NOT NULL DEFAULT 'SGD',
    branch_id       VARCHAR(50)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Set on delete; the row and its status are kept, but every API treats it
    -- as missing. Deleted is not the same as status 'closed' (ADR-0036).
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT ck_accounts_type
        CHECK (account_type IN ('savings', 'checking', 'business', 'credit', 'loan', 'investment')),
    CONSTRAINT ck_accounts_status
        CHECK (status IN ('active', 'inactive', 'pending', 'closed')),
    CONSTRAINT ck_accounts_initial_deposit
        CHECK (initial_deposit >= 0),
    CONSTRAINT ck_accounts_currency
        CHECK (currency ~ '^[A-Z]{3}$')
);

-- One account per type per client, not counting deleted accounts.
CREATE UNIQUE INDEX uk_accounts_client_type ON accounts (client_id, account_type) WHERE deleted_at IS NULL;

CREATE INDEX idx_accounts_client_id ON accounts (client_id);
CREATE INDEX idx_accounts_owner_agent_id ON accounts (owner_agent_id);
CREATE INDEX idx_accounts_branch_id ON accounts (branch_id);
CREATE INDEX idx_accounts_updated_at ON accounts (updated_at DESC);
-- The business dashboard counts accounts by opening date (ADR-0038).
CREATE INDEX idx_accounts_opening_date ON accounts (opening_date DESC);

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
