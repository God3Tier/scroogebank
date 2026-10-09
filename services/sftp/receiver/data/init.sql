CREATE TABLE transactions (
    transaction_id   TEXT PRIMARY KEY,
    source_account_ref TEXT NOT NULL,
    client_id         UUID NULL,
    transaction_type  TEXT NOT NULL
        CHECK (transaction_type IN ('Deposit', 'Withdrawal')),
    amount            NUMERIC(18, 2) NOT NULL CHECK (amount >= 0),
    currency          CHAR(3),
    transaction_date  TIMESTAMPTZ NOT NULL,
    status            TEXT NOT NULL
        CHECK (status IN ('Completed', 'Pending', 'Failed')),
    description       TEXT,
    imported_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    import_status     TEXT NOT NULL DEFAULT 'Unmatched'
        CHECK (import_status IN ('Unmatched', 'Matched', 'Rejected')),
    -- The CRM account the row belongs to, set at import when account-service
    -- confirms source_account_ref is one of its accounts (ADR-0037). Accounts
    -- live in account-service's database, so there is no foreign key.
    account_id        UUID NULL
    );

CREATE INDEX idx_transactions_account_date
    ON transactions (account_id, transaction_date DESC);

-- The business dashboard's bank-wide totals filter on the date alone (ADR-0038).
CREATE INDEX idx_transactions_date
    ON transactions (transaction_date DESC);

ALTER TABLE transactions
    ALTER COLUMN transaction_id TYPE TEXT USING transaction_id::text;

-- Ingestion audit events waiting to be sent to the Auditor's SQS queue
-- (ADR-0020 decision 3). A file's FILE_PROCESSING_COMPLETED event is written in
-- the same transaction as its transactions; processing errors, which have no
-- import to join, are written on their own. The poller sends every waiting row
-- at the end of each poll and deletes it once SQS has accepted it. event_id is
-- the Auditor's de-duplication key, so a row sent twice is stored only once.
CREATE TABLE audit_outbox (
    event_id    TEXT        PRIMARY KEY,
    body        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_outbox_created_at ON audit_outbox (created_at);
