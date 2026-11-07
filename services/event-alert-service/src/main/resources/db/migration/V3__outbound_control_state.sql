-- Monthly outbound usage and disabled capabilities are shared by every process
-- (the API and the run-once worker), so they live in the database, not in memory.
CREATE TABLE outbound_usage (
    usage_month CHAR(7) PRIMARY KEY CHECK (usage_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    search_queries INTEGER NOT NULL DEFAULT 0 CHECK (search_queries >= 0),
    model_tokens BIGINT NOT NULL DEFAULT 0 CHECK (model_tokens >= 0),
    estimated_cost_usd NUMERIC(14, 6) NOT NULL DEFAULT 0 CHECK (estimated_cost_usd >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbound_control (
    capability VARCHAR(20) PRIMARY KEY CHECK (capability IN ('INGESTION', 'MODEL')),
    reason VARCHAR(40) NOT NULL CHECK (reason IN (
        'OPERATOR_DISABLED', 'SEARCH_QUOTA_REACHED',
        'MODEL_TOKEN_QUOTA_REACHED', 'MONTHLY_BUDGET_REACHED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
