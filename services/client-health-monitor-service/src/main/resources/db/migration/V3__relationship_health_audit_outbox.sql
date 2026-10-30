CREATE TABLE relationship_health_audit_outbox (
    event_id CHAR(64) PRIMARY KEY,
    event_type TEXT NOT NULL,
    action TEXT NOT NULL,
    source_service TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token TEXT,
    lease_expires_at TIMESTAMPTZ,
    last_failure_category TEXT,
    CONSTRAINT ck_relationship_health_audit_outbox_event_id
        CHECK (event_id ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_relationship_health_audit_outbox_event_type
        CHECK (event_type = 'RELATIONSHIP_HEALTH'),
    CONSTRAINT ck_relationship_health_audit_outbox_source
        CHECK (source_service = 'RELATIONSHIP_HEALTH_SERVICE'),
    CONSTRAINT ck_relationship_health_audit_outbox_action
        CHECK (action IN (
            'ANALYSIS_RUN_STARTED',
            'ANALYSIS_RUN_COMPLETED',
            'ANALYSIS_RUN_FAILED',
            'SIGNAL_DETECTED',
            'SIGNAL_SUPPRESSED',
            'TASK_CREATED',
            'TASK_EVIDENCE_ADDED',
            'TASK_ACCEPTED',
            'TASK_COMPLETED',
            'TASK_DISMISSED',
            'EXPLANATION_GENERATED',
            'EXPLANATION_FALLBACK_USED',
            'OUTBOX_DELIVERY_RECOVERED'
        )),
    CONSTRAINT ck_relationship_health_audit_outbox_payload
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_relationship_health_audit_outbox_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_relationship_health_audit_outbox_lease
        CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT ck_relationship_health_audit_outbox_failure
        CHECK (last_failure_category IS NULL OR last_failure_category IN (
            'DELIVERY_UNAVAILABLE',
            'DELIVERY_REJECTED',
            'UNKNOWN'
        ))
);

CREATE INDEX ix_relationship_health_audit_outbox_ready
    ON relationship_health_audit_outbox (next_attempt_at, created_at)
    WHERE lease_token IS NULL;

CREATE INDEX ix_relationship_health_audit_outbox_expired_lease
    ON relationship_health_audit_outbox (lease_expires_at)
    WHERE lease_token IS NOT NULL;
