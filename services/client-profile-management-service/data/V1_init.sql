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
    created_by_agent_id   UUID         NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_clients_email UNIQUE (email),
    CONSTRAINT uk_clients_phone UNIQUE (phone_number)
);

CREATE INDEX idx_clients_created_by_agent_id ON clients (created_by_agent_id);
CREATE INDEX idx_clients_updated_at ON clients (updated_at DESC);
