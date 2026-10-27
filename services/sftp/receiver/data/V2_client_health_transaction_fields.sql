ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS activity_kind TEXT NOT NULL DEFAULT 'Unknown'
        CHECK (activity_kind IN ('CustomerInitiated', 'Automated', 'Unknown')),
    ADD COLUMN IF NOT EXISTS source_fingerprint TEXT NULL
        CHECK (source_fingerprint IS NULL OR (
            char_length(source_fingerprint) BETWEEN 24 AND 64
            AND source_fingerprint ~ '^synthetic-[a-z0-9]+(-[a-z0-9]+)*-[a-f0-9]{12}$'
        ));

DROP INDEX IF EXISTS idx_transactions_client_date;
CREATE INDEX IF NOT EXISTS idx_transactions_history_date
    ON transactions (transaction_date DESC, transaction_id DESC)
    WHERE import_status = 'Matched' AND client_id IS NOT NULL AND account_id IS NOT NULL;
