ALTER TABLE relationship_task_evidence
    DROP CONSTRAINT ck_relationship_task_evidence_baseline,
    DROP CONSTRAINT ck_relationship_task_evidence_comparison,
    ADD CONSTRAINT ck_relationship_task_evidence_baseline
        CHECK (baseline_to > baseline_from),
    ADD CONSTRAINT ck_relationship_task_evidence_comparison
        CHECK (comparison_to > comparison_from),
    ADD CONSTRAINT ck_relationship_task_evidence_window_order
        CHECK (baseline_to <= comparison_from),
    ADD CONSTRAINT ck_relationship_task_evidence_signal_type
        CHECK (signal_type IN (
            'RECURRING_CREDIT_INTERRUPTION',
            'TRANSACTION_ACTIVITY_DECLINE',
            'SUSTAINED_NET_OUTFLOW'
        )),
    ADD CONSTRAINT ck_relationship_task_evidence_fingerprint
        CHECK (fingerprint ~ '^[a-f0-9]{64}$');

CREATE INDEX ix_relationship_tasks_client_updated
    ON relationship_tasks (client_id, updated_at DESC)
    WHERE status IN ('COMPLETED', 'DISMISSED');
