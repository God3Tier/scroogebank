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
