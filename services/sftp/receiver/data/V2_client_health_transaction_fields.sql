ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS activity_kind TEXT NOT NULL DEFAULT 'Unknown'
        CHECK (activity_kind IN ('CustomerInitiated', 'Automated', 'Unknown')),
    ADD COLUMN IF NOT EXISTS source_fingerprint TEXT NULL;

CREATE INDEX IF NOT EXISTS idx_transactions_client_date
    ON transactions (client_id, transaction_date DESC, transaction_id DESC)
    WHERE import_status = 'Matched' AND client_id IS NOT NULL AND account_id IS NOT NULL;
