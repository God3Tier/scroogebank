CREATE TABLE client_health_activity_projection (
    transaction_id TEXT PRIMARY KEY,
    client_id TEXT NOT NULL,
    account_id TEXT NOT NULL,
    transaction_type TEXT NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    transaction_date TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL,
    activity_kind TEXT NOT NULL,
    source_fingerprint TEXT,
    CONSTRAINT ck_client_health_activity_type
        CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT ck_client_health_activity_amount CHECK (amount >= 0),
    CONSTRAINT ck_client_health_activity_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_client_health_activity_status
        CHECK (status IN ('COMPLETED', 'PENDING', 'FAILED')),
    CONSTRAINT ck_client_health_activity_kind
        CHECK (activity_kind IN ('CUSTOMER_INITIATED', 'AUTOMATED', 'UNKNOWN')),
    CONSTRAINT ck_client_health_activity_source
        CHECK (source_fingerprint IS NULL OR source_fingerprint ~
            '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
);

CREATE INDEX ix_client_health_activity_client_date
    ON client_health_activity_projection (client_id, transaction_date, transaction_id);

CREATE INDEX ix_client_health_activity_account_date
    ON client_health_activity_projection (account_id, transaction_date, transaction_id);

CREATE TABLE client_health_replay_coverage (
    coverage_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    from_inclusive TIMESTAMPTZ NOT NULL,
    to_exclusive TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_client_health_replay_coverage
        UNIQUE (from_inclusive, to_exclusive),
    CONSTRAINT ck_client_health_replay_coverage_window
        CHECK (from_inclusive < to_exclusive)
);

CREATE INDEX ix_client_health_replay_coverage_bounds
    ON client_health_replay_coverage (from_inclusive, to_exclusive);

CREATE TABLE relationship_health_analysis_runs (
    run_id TEXT PRIMARY KEY,
    analysis_cutoff TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL,
    lease_token TEXT,
    lease_expires_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL,
    watermark TIMESTAMPTZ,
    failure_category TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_relationship_health_analysis_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_relationship_health_analysis_attempts CHECK (attempts > 0),
    CONSTRAINT ck_relationship_health_analysis_times CHECK (updated_at >= created_at),
    CONSTRAINT ck_relationship_health_analysis_failure CHECK (
        failure_category IS NULL OR failure_category IN (
            'REPLAY',
            'PROJECTION',
            'BASELINE',
            'RULE_EVALUATION',
            'TASK_HANDOFF',
            'UNEXPECTED'
        )
    ),
    CONSTRAINT ck_relationship_health_analysis_state CHECK (
        (status = 'RUNNING'
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND lease_expires_at > updated_at
            AND watermark IS NULL
            AND failure_category IS NULL) OR
        (status = 'SUCCEEDED'
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
            AND watermark = analysis_cutoff
            AND failure_category IS NULL) OR
        (status = 'FAILED'
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
            AND watermark IS NULL
            AND failure_category IS NOT NULL)
    )
);

CREATE INDEX ix_relationship_health_analysis_expired_lease
    ON relationship_health_analysis_runs (lease_expires_at)
    WHERE status = 'RUNNING';

CREATE INDEX ix_relationship_health_analysis_watermark
    ON relationship_health_analysis_runs (watermark DESC)
    WHERE status = 'SUCCEEDED';
