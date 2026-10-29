CREATE TABLE relationship_task_explanations (
    explanation_id CHAR(64) PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES relationship_tasks (task_id),
    finding_id TEXT NOT NULL REFERENCES processed_client_health_findings (finding_id),
    task_version BIGINT NOT NULL,
    summary TEXT NOT NULL,
    suggested_review_step TEXT NOT NULL,
    disclaimer TEXT NOT NULL,
    evidence_codes TEXT[] NOT NULL,
    source TEXT NOT NULL,
    prompt_template_version TEXT NOT NULL,
    model_id TEXT,
    generated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_relationship_task_explanation_version
        UNIQUE (task_id, finding_id, task_version, prompt_template_version),
    CONSTRAINT ck_relationship_task_explanation_id
        CHECK (explanation_id ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_relationship_task_explanation_version CHECK (task_version > 0),
    CONSTRAINT ck_relationship_task_explanation_text CHECK (
        btrim(summary) <> '' AND btrim(suggested_review_step) <> ''
        AND disclaimer = 'Human review required'
        AND char_length(summary) + char_length(suggested_review_step)
            + char_length(disclaimer) + 3 <= 400),
    CONSTRAINT ck_relationship_task_explanation_codes CHECK (
        cardinality(evidence_codes) > 0
        AND evidence_codes <@ ARRAY[
            'RECURRING_CREDIT_INTERRUPTION',
            'TRANSACTION_ACTIVITY_DECLINE',
            'SUSTAINED_NET_OUTFLOW']::TEXT[]),
    CONSTRAINT ck_relationship_task_explanation_source CHECK (
        (source = 'BEDROCK' AND model_id IS NOT NULL AND btrim(model_id) <> '') OR
        (source = 'TEMPLATE_FALLBACK' AND model_id IS NULL)),
    CONSTRAINT ck_relationship_task_explanation_prompt
        CHECK (btrim(prompt_template_version) <> '')
);

CREATE INDEX ix_relationship_task_explanations_current
    ON relationship_task_explanations (task_id, task_version DESC);
