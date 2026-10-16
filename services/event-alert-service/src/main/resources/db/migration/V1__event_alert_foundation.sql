CREATE TABLE search_run (
    id UUID PRIMARY KEY,
    window_from TIMESTAMPTZ NOT NULL,
    window_to TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    lease_until TIMESTAMPTZ,
    search_count INTEGER NOT NULL DEFAULT 0 CHECK (search_count >= 0),
    result_count INTEGER NOT NULL DEFAULT 0 CHECK (result_count >= 0),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_search_run_window UNIQUE (window_from, window_to)
);

CREATE TABLE source_result (
    id UUID PRIMARY KEY,
    search_run_id UUID NOT NULL REFERENCES search_run(id),
    provider_result_id VARCHAR(128),
    canonical_url VARCHAR(2048) NOT NULL,
    canonical_url_hash CHAR(64) NOT NULL UNIQUE,
    title VARCHAR(300) NOT NULL,
    source_domain VARCHAR(253) NOT NULL,
    published_at TIMESTAMPTZ,
    retrieved_at TIMESTAMPTZ NOT NULL,
    content_hash CHAR(64),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE public_event (
    id UUID PRIMARY KEY,
    source_result_id UUID NOT NULL REFERENCES source_result(id),
    classification VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    confidence NUMERIC(5,4) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    classifier_version VARCHAR(100) NOT NULL,
    model_version VARCHAR(100),
    prompt_version VARCHAR(50),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_public_event_source_classifier UNIQUE (source_result_id, classifier_version)
);

CREATE TABLE instrument (
    id VARCHAR(32) PRIMARY KEY,
    ticker VARCHAR(32) NOT NULL,
    issuer_name VARCHAR(200) NOT NULL,
    sector VARCHAR(100) NOT NULL,
    currency CHAR(3) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_instrument_ticker UNIQUE (ticker)
);

CREATE TABLE public_event_instrument (
    public_event_id UUID NOT NULL REFERENCES public_event(id),
    instrument_id VARCHAR(32) NOT NULL REFERENCES instrument(id),
    match_reason VARCHAR(300) NOT NULL,
    confidence NUMERIC(5,4) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    PRIMARY KEY (public_event_id, instrument_id)
);

CREATE TABLE synthetic_portfolio (
    client_ref VARCHAR(64) PRIMARY KEY,
    account_ref VARCHAR(64) NOT NULL UNIQUE,
    assigned_agent_subject VARCHAR(128) NOT NULL,
    source_version VARCHAR(50) NOT NULL
);

CREATE TABLE holding_snapshot (
    id UUID PRIMARY KEY,
    client_ref VARCHAR(64) NOT NULL REFERENCES synthetic_portfolio(client_ref),
    account_ref VARCHAR(64) NOT NULL,
    assigned_agent_subject VARCHAR(128) NOT NULL,
    instrument_id VARCHAR(32) NOT NULL REFERENCES instrument(id),
    quantity NUMERIC(24,8) NOT NULL CHECK (quantity >= 0),
    unit_price NUMERIC(24,8) NOT NULL CHECK (unit_price >= 0),
    valuation_currency VARCHAR(3) NOT NULL,
    fx_to_sgd NUMERIC(24,8) NOT NULL CHECK (fx_to_sgd > 0),
    value_sgd NUMERIC(19,4) NOT NULL CHECK (value_sgd >= 0),
    valued_at TIMESTAMPTZ NOT NULL,
    freshness VARCHAR(16) NOT NULL CHECK (freshness IN ('FRESH', 'STALE', 'EXPIRED', 'MISSING')),
    source_version VARCHAR(50) NOT NULL
);

CREATE INDEX idx_holding_snapshot_instrument ON holding_snapshot(instrument_id);
CREATE INDEX idx_holding_snapshot_agent ON holding_snapshot(assigned_agent_subject);

CREATE TABLE alert (
    id UUID PRIMARY KEY,
    client_ref VARCHAR(64) NOT NULL,
    assigned_agent_subject VARCHAR(128) NOT NULL,
    public_event_id UUID NOT NULL REFERENCES public_event(id),
    instrument_id VARCHAR(32) NOT NULL REFERENCES instrument(id),
    holding_snapshot_id UUID NOT NULL REFERENCES holding_snapshot(id),
    score NUMERIC(5,2) NOT NULL CHECK (score >= 0 AND score <= 100),
    rule_version VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('NEW', 'MONITORING', 'INFORMED', 'DISMISSED')),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_alert_client_event_instrument UNIQUE (client_ref, public_event_id, instrument_id)
);

CREATE INDEX idx_alert_agent_created ON alert(assigned_agent_subject, created_at DESC);
CREATE INDEX idx_alert_public_event ON alert(public_event_id);

CREATE TABLE alert_factor (
    alert_id UUID NOT NULL REFERENCES alert(id),
    sequence_number INTEGER NOT NULL CHECK (sequence_number >= 0),
    code VARCHAR(64) NOT NULL,
    contribution NUMERIC(7,2) NOT NULL,
    description VARCHAR(300) NOT NULL,
    PRIMARY KEY (alert_id, sequence_number)
);

CREATE TABLE briefing (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL UNIQUE REFERENCES alert(id),
    summary VARCHAR(1500) NOT NULL,
    uncertainty VARCHAR(500),
    model_version VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    ai_generated BOOLEAN NOT NULL CHECK (ai_generated),
    citation_refs JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE alert_decision (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL REFERENCES alert(id),
    actor_subject VARCHAR(128) NOT NULL,
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('INFORM_CLIENT', 'MONITOR', 'DISMISS')),
    previous_status VARCHAR(20) NOT NULL,
    new_status VARCHAR(20) NOT NULL,
    note VARCHAR(500),
    idempotency_key VARCHAR(128) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    alert_version BIGINT NOT NULL CHECK (alert_version >= 1),
    CONSTRAINT uk_alert_decision_actor_idempotency UNIQUE (actor_subject, idempotency_key)
);

CREATE TABLE alert_feedback (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL REFERENCES alert(id),
    actor_subject VARCHAR(128) NOT NULL,
    rating VARCHAR(16) NOT NULL CHECK (rating IN ('USEFUL', 'NOT_USEFUL')),
    reason_code VARCHAR(32),
    comment VARCHAR(500),
    idempotency_key VARCHAR(128) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    alert_version BIGINT NOT NULL CHECK (alert_version >= 0),
    CONSTRAINT uk_alert_feedback_actor_idempotency UNIQUE (actor_subject, idempotency_key)
);

CREATE TABLE processing_watermark (
    provider VARCHAR(64) PRIMARY KEY,
    last_successful_to TIMESTAMPTZ NOT NULL,
    overlap_seconds INTEGER NOT NULL CHECK (overlap_seconds >= 0),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE audit_outbox (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    event_version INTEGER NOT NULL CHECK (event_version > 0),
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'LEASED', 'DELIVERED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ
);

CREATE INDEX idx_audit_outbox_pending ON audit_outbox(status, created_at)
    WHERE status IN ('PENDING', 'FAILED');
