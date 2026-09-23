-- Clients
-- Everything encrypted, hash the unique field
CREATE TABLE clients (
    client_id             UUID PRIMARY KEY,
    first_name            VARCHAR(255) NOT NULL,
    last_name             VARCHAR(255) NOT NULL,
    date_of_birth         VARCHAR(255) NOT NULL,
    gender                VARCHAR(255) NOT NULL,
    email                 VARCHAR(255) NOT NULL,
    email_hash            VARCHAR(255) NOT NULL,
    phone_number          VARCHAR(255) NOT NULL,
    phone_number_hash     VARCHAR(255) NOT NULL,
    address               VARCHAR(255) NOT NULL,
    city                  VARCHAR(255) NOT NULL,
    state                 VARCHAR(255) NOT NULL,
    country               VARCHAR(255) NOT NULL,
    postal_code           VARCHAR(255) NOT NULL,
    verification_status   VARCHAR(255) NOT NULL DEFAULT 'UNVERIFIED',
    created_by_agent_id   UUID         NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_clients_email UNIQUE (email_hash),
    CONSTRAINT uk_clients_phone UNIQUE (phone_number_hash)
);

CREATE INDEX idx_clients_created_by_agent_id ON clients (created_by_agent_id);
CREATE INDEX idx_clients_updated_at ON clients (updated_at DESC);

-- Client Keys
CREATE TABLE client_keys (
    client_id         UUID PRIMARY KEY,
    key_value         VARCHAR(255)   NOT NULL,

    CONSTRAINT fk_client_keys_client_id FOREIGN KEY (client_id) REFERENCES clients (client_id) ON DELETE CASCADE
);
CREATE INDEX idx_client_keys_client_id ON client_keys (client_id);

-- Accounts
CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    client_id       UUID           NOT NULL,
    account_type    VARCHAR(20)    NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'active',
    opening_date    DATE           NOT NULL DEFAULT CURRENT_DATE,
    initial_deposit NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency        CHAR(3)        NOT NULL DEFAULT 'SGD',
    branch_id       VARCHAR(50)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_accounts_client
        FOREIGN KEY (client_id) REFERENCES clients (client_id)
        ON DELETE RESTRICT,
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
CREATE INDEX idx_accounts_branch_id ON accounts (branch_id);
CREATE INDEX idx_accounts_updated_at ON accounts (updated_at DESC);
