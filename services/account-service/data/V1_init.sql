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
    -- Cognito sub of the agent who owns client_id, copied at creation.
    -- VARCHAR(128) to match clients.created_by_agent_id: Cognito does not
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

    CONSTRAINT ck_accounts_type
        CHECK (account_type IN ('savings', 'checking', 'business', 'credit', 'loan', 'investment')),
    CONSTRAINT ck_accounts_status
        CHECK (status IN ('active', 'inactive', 'pending', 'closed')),
    CONSTRAINT ck_accounts_initial_deposit
        CHECK (initial_deposit >= 0),
    CONSTRAINT ck_accounts_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT uk_accounts_client_type
        UNIQUE (client_id, account_type)
);

CREATE INDEX idx_accounts_client_id ON accounts (client_id);
CREATE INDEX idx_accounts_owner_agent_id ON accounts (owner_agent_id);
CREATE INDEX idx_accounts_branch_id ON accounts (branch_id);
CREATE INDEX idx_accounts_updated_at ON accounts (updated_at DESC);
