-- Clients
-- The 11 personal fields hold AES-256-GCM ciphertext, encrypted with the
-- client's own data key (ADR-0030); their lengths are checked by the service,
-- not here. email_hash and phone_number_hash are blind indexes: keyed hashes
-- of the normalised email and phone, so they can stay unique without being
-- readable.
CREATE TABLE clients (
    client_id             UUID PRIMARY KEY,
    first_name            TEXT         NOT NULL,
    last_name             TEXT         NOT NULL,
    date_of_birth         TEXT         NOT NULL,
    gender                TEXT         NOT NULL,
    email                 TEXT         NOT NULL,
    email_hash            TEXT         NOT NULL,
    phone_number          TEXT         NOT NULL,
    phone_number_hash     TEXT         NOT NULL,
    address               TEXT         NOT NULL,
    city                  TEXT         NOT NULL,
    state                 TEXT         NOT NULL,
    country               TEXT         NOT NULL,
    postal_code           TEXT         NOT NULL,
    verification_status   VARCHAR(20)  NOT NULL DEFAULT 'UNVERIFIED',
    -- Who verified the client, when, and whether the verification named any
    -- identity documents (ADR-0041). Documents are optional, so false means
    -- verified without one. All NULL until verified, and for clients
    -- verified before ADR-0041.
    verified_by_agent_id  VARCHAR(128),
    verified_at           TIMESTAMPTZ,
    verified_with_documents BOOLEAN,
    created_by_agent_id   VARCHAR(128) NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    -- Set on delete; the row is kept but every API treats it as missing (ADR-0036).
    deleted_at            TIMESTAMPTZ
);

-- Unique among clients that are not deleted, so a deleted client's details can be reused.
CREATE UNIQUE INDEX uk_clients_email ON clients (email_hash) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_clients_phone ON clients (phone_number_hash) WHERE deleted_at IS NULL;
CREATE INDEX idx_clients_created_by_agent_id ON clients (created_by_agent_id);
CREATE INDEX idx_clients_updated_at ON clients (updated_at DESC);
-- The business dashboard counts clients by creation date (ADR-0038).
CREATE INDEX idx_clients_created_at ON clients (created_at DESC);

-- Identity documents, the evidence an agent checks before verifying a client
-- (ADR-0041). The file itself is in S3, encrypted with the client's data key,
-- at clients/{client_id}/{document_id}; this row never holds it or its name.
-- verification_evidence marks the documents named when the client was
-- verified. The API never changes or deletes a document; the cascade only
-- applies when a client row is erased.
CREATE TABLE client_documents (
    document_id            UUID         PRIMARY KEY,
    client_id              UUID         NOT NULL REFERENCES clients (client_id) ON DELETE CASCADE,
    content_type           VARCHAR(50)  NOT NULL,
    size_bytes             INTEGER      NOT NULL,
    uploaded_by_agent_id   VARCHAR(128) NOT NULL,
    uploaded_at            TIMESTAMPTZ  NOT NULL,
    verification_evidence  BOOLEAN      NOT NULL DEFAULT false
);

CREATE INDEX idx_client_documents_client_id ON client_documents (client_id, uploaded_at DESC);

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
