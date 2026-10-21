CREATE TABLE relationship_tasks (
    task_id TEXT PRIMARY KEY,
    client_id TEXT NOT NULL,
    status TEXT NOT NULL,
    severity TEXT NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_relationship_tasks_task_client UNIQUE (task_id, client_id),
    CONSTRAINT ck_relationship_tasks_status
        CHECK (status IN ('OPEN', 'IN_REVIEW', 'COMPLETED', 'DISMISSED')),
    CONSTRAINT ck_relationship_tasks_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_relationship_tasks_version CHECK (version > 0),
    CONSTRAINT ck_relationship_tasks_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_relationship_tasks_active_client
    ON relationship_tasks (client_id)
    WHERE status IN ('OPEN', 'IN_REVIEW');

CREATE INDEX ix_relationship_tasks_status_updated
    ON relationship_tasks (status, updated_at DESC);

CREATE TABLE task_assignment_snapshots (
    task_id TEXT PRIMARY KEY,
    client_id TEXT NOT NULL,
    agent_subject TEXT NOT NULL,
    assignment_active BOOLEAN NOT NULL,
    assignment_version BIGINT NOT NULL,
    assignment_updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_task_assignment_snapshots_task
        FOREIGN KEY (task_id, client_id)
        REFERENCES relationship_tasks (task_id, client_id),
    CONSTRAINT ck_task_assignment_snapshots_version CHECK (assignment_version > 0)
);

CREATE INDEX ix_task_assignment_snapshots_agent
    ON task_assignment_snapshots (agent_subject, task_id);

CREATE TABLE relationship_task_evidence (
    evidence_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id TEXT NOT NULL,
    client_id TEXT NOT NULL,
    finding_id TEXT NOT NULL,
    analysis_run_id TEXT NOT NULL,
    ruleset_version TEXT NOT NULL,
    correlation_id TEXT NOT NULL,
    finding_detected_at TIMESTAMPTZ NOT NULL,
    attached_at TIMESTAMPTZ NOT NULL,
    signal_id TEXT NOT NULL,
    account_id TEXT NOT NULL,
    signal_type TEXT NOT NULL,
    rule_version TEXT NOT NULL,
    severity TEXT NOT NULL,
    baseline_from TIMESTAMPTZ NOT NULL,
    baseline_to TIMESTAMPTZ NOT NULL,
    comparison_from TIMESTAMPTZ NOT NULL,
    comparison_to TIMESTAMPTZ NOT NULL,
    evidence_payload JSONB NOT NULL,
    fingerprint TEXT NOT NULL,
    CONSTRAINT fk_relationship_task_evidence_task
        FOREIGN KEY (task_id, client_id)
        REFERENCES relationship_tasks (task_id, client_id),
    CONSTRAINT uq_relationship_task_evidence_client_fingerprint
        UNIQUE (client_id, fingerprint),
    CONSTRAINT ck_relationship_task_evidence_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_relationship_task_evidence_baseline
        CHECK (baseline_to >= baseline_from),
    CONSTRAINT ck_relationship_task_evidence_comparison
        CHECK (comparison_to >= comparison_from),
    CONSTRAINT ck_relationship_task_evidence_attached
        CHECK (attached_at >= finding_detected_at),
    CONSTRAINT ck_relationship_task_evidence_payload
        CHECK (jsonb_typeof(evidence_payload) = 'object')
);

CREATE INDEX ix_relationship_task_evidence_task
    ON relationship_task_evidence (task_id, evidence_id);

CREATE TABLE relationship_task_history (
    task_id TEXT NOT NULL REFERENCES relationship_tasks (task_id),
    task_version BIGINT NOT NULL,
    action TEXT NOT NULL,
    actor_subject TEXT NOT NULL,
    correlation_id TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    status_before TEXT,
    status_after TEXT NOT NULL,
    severity_before TEXT,
    severity_after TEXT NOT NULL,
    added_fingerprints TEXT[] NOT NULL DEFAULT '{}',
    disposition_code TEXT,
    note TEXT,
    PRIMARY KEY (task_id, task_version),
    CONSTRAINT ck_relationship_task_history_version CHECK (task_version > 0),
    CONSTRAINT ck_relationship_task_history_action
        CHECK (action IN ('CREATED', 'EVIDENCE_ADDED', 'ACCEPTED', 'COMPLETED', 'DISMISSED')),
    CONSTRAINT ck_relationship_task_history_status_before
        CHECK (status_before IS NULL OR status_before IN ('OPEN', 'IN_REVIEW', 'COMPLETED', 'DISMISSED')),
    CONSTRAINT ck_relationship_task_history_status_after
        CHECK (status_after IN ('OPEN', 'IN_REVIEW', 'COMPLETED', 'DISMISSED')),
    CONSTRAINT ck_relationship_task_history_severity_before
        CHECK (severity_before IS NULL OR severity_before IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_relationship_task_history_severity_after
        CHECK (severity_after IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_relationship_task_history_transition CHECK (
        (action = 'CREATED' AND status_before IS NULL AND status_after = 'OPEN'
            AND disposition_code IS NULL) OR
        (action = 'EVIDENCE_ADDED' AND status_before = status_after
            AND status_after IN ('OPEN', 'IN_REVIEW') AND disposition_code IS NULL) OR
        (action = 'ACCEPTED' AND status_before = 'OPEN' AND status_after = 'IN_REVIEW'
            AND disposition_code IS NULL) OR
        (action = 'COMPLETED' AND status_before = 'IN_REVIEW' AND status_after = 'COMPLETED'
            AND disposition_code IS NOT NULL AND btrim(disposition_code) <> '') OR
        (action = 'DISMISSED' AND status_before IN ('OPEN', 'IN_REVIEW')
            AND status_after = 'DISMISSED'
            AND disposition_code IS NOT NULL AND btrim(disposition_code) <> '')
    )
);

CREATE INDEX ix_relationship_task_history_occurred
    ON relationship_task_history (task_id, occurred_at DESC);

CREATE TABLE processed_client_health_findings (
    finding_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES relationship_tasks (task_id),
    finding_payload JSONB NOT NULL,
    outcome TEXT NOT NULL,
    result_version BIGINT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_processed_client_health_findings_outcome
        CHECK (outcome IN ('CREATED', 'EVIDENCE_ATTACHED', 'DUPLICATE')),
    CONSTRAINT ck_processed_client_health_findings_version CHECK (result_version > 0),
    CONSTRAINT ck_processed_client_health_findings_payload
        CHECK (jsonb_typeof(finding_payload) = 'object')
);

CREATE INDEX ix_processed_client_health_findings_task
    ON processed_client_health_findings (task_id, processed_at DESC);
