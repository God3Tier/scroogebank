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
        CHECK (import_status IN ('Unmatched', 'Matched', 'Rejected'))
    );

ALTER TABLE transactions
    ALTER COLUMN transaction_id TYPE TEXT USING transaction_id::text;
