-- CRM-linked current holdings (ADR-0066). Additive: V1 synthetic_portfolio and every
-- existing holding_snapshot / alert row are retained unchanged.

CREATE TABLE current_portfolio (
    account_id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    source_label VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128) NOT NULL
);

CREATE INDEX idx_current_portfolio_client ON current_portfolio(client_id);

CREATE TABLE current_position (
    account_id UUID NOT NULL REFERENCES current_portfolio(account_id) ON DELETE CASCADE,
    instrument_id VARCHAR(32) NOT NULL REFERENCES instrument(id),
    quantity NUMERIC(24,8) NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(24,8) NOT NULL CHECK (unit_price > 0),
    valuation_currency VARCHAR(3) NOT NULL,
    fx_to_sgd NUMERIC(24,8) NOT NULL CHECK (fx_to_sgd > 0),
    value_sgd NUMERIC(19,4) NOT NULL CHECK (value_sgd > 0),
    valued_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, instrument_id)
);

CREATE INDEX idx_current_position_instrument ON current_position(instrument_id);

-- One replayable record of every complete replacement, keyed per actor.
CREATE TABLE portfolio_write_request (
    actor_subject VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    account_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    resulting_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (actor_subject, idempotency_key)
);

-- Group of holding_snapshot rows that were scored together as one account portfolio.
CREATE TABLE portfolio_snapshot (
    id UUID PRIMARY KEY,
    account_ref VARCHAR(64) NOT NULL,
    client_ref VARCHAR(64) NOT NULL,
    portfolio_revision BIGINT NOT NULL,
    assigned_agent_subject VARCHAR(128) NOT NULL,
    total_sgd NUMERIC(19,4) NOT NULL CHECK (total_sgd > 0),
    created_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE holding_snapshot DROP CONSTRAINT IF EXISTS holding_snapshot_client_ref_fkey;
ALTER TABLE holding_snapshot ADD COLUMN portfolio_snapshot_id UUID REFERENCES portfolio_snapshot(id);
ALTER TABLE holding_snapshot ADD COLUMN portfolio_revision BIGINT;
CREATE INDEX idx_holding_snapshot_group ON holding_snapshot(portfolio_snapshot_id);

-- Minimal, refreshable copy of "who currently owns this CRM client". Never a
-- long-lived positive cache: refreshed before each list/read/mutation.
CREATE TABLE client_assignment (
    client_ref VARCHAR(64) PRIMARY KEY,
    agent_subject VARCHAR(128),
    checked_at TIMESTAMPTZ NOT NULL
);

-- Agent-level event action (Inform / Monitor / Dismiss on an explicit client set).
CREATE TABLE agent_event_action (
    id UUID PRIMARY KEY,
    public_event_id UUID NOT NULL REFERENCES public_event(id),
    actor_subject VARCHAR(128) NOT NULL,
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('INFORM_CLIENT', 'MONITOR', 'DISMISS')),
    note VARCHAR(500),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_agent_event_action_actor_key UNIQUE (actor_subject, idempotency_key)
);

CREATE TABLE agent_event_action_member (
    action_id UUID NOT NULL REFERENCES agent_event_action(id),
    alert_id UUID NOT NULL REFERENCES alert(id),
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('APPLIED', 'STALE_VERSION', 'INVALID_TRANSITION', 'NOT_AUTHORISED')),
    previous_status VARCHAR(20),
    new_status VARCHAR(20),
    PRIMARY KEY (action_id, alert_id)
);
