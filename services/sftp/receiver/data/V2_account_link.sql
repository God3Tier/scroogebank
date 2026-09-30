ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS account_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_transactions_account'
    ) THEN
        ALTER TABLE transactions
            ADD CONSTRAINT fk_transactions_account
            FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE RESTRICT;
    END IF;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_transactions_account_date
    ON transactions (account_id, transaction_date DESC);

CREATE OR REPLACE FUNCTION link_transaction_to_account()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    SELECT id INTO NEW.account_id
    FROM accounts
    WHERE lower(id::text) = lower(NEW.source_account_ref);

    IF NOT FOUND THEN
        NEW.account_id := NULL;
        NEW.import_status := 'Unmatched';
    ELSE
        NEW.import_status := 'Matched';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS transactions_link_account ON transactions;
CREATE TRIGGER transactions_link_account
    BEFORE INSERT OR UPDATE OF source_account_ref ON transactions
    FOR EACH ROW EXECUTE FUNCTION link_transaction_to_account();

UPDATE transactions AS tx
SET account_id = account.id,
    import_status = 'Matched'
FROM accounts AS account
WHERE tx.account_id IS NULL
  AND lower(account.id::text) = lower(tx.source_account_ref);
