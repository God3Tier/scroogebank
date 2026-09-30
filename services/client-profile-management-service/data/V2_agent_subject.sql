-- Cognito's stable `sub` is the authenticated agent identifier. Store it as
-- text because Cognito does not guarantee that `sub` follows PostgreSQL UUID
-- formatting rules.
ALTER TABLE clients
    ALTER COLUMN created_by_agent_id TYPE VARCHAR(128)
    USING created_by_agent_id::text;
