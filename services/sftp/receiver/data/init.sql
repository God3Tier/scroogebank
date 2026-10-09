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
