# ADR-0027: Observability baseline: structured logs, metrics, alarms to SNS

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

NFR 7 asks for monitoring, dashboards and alerting mechanisms. Several designs depend on someone reacting to an alarm:
- the audit dead-letter queue ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md));
- `AUDIT_SEND_FAILED` from the Cognito triggers;
- chain-verify mismatches ([ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md));
- ingestion failures ([ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md));
- budget alerts.

Logs must not contain client personal data (NFR 8).

## Decision Drivers

- NFR 7 (monitoring, dashboards, alerting)
- Privacy: no PII in logs
- Cost and operational simplicity

## Options Considered

### Option A: CloudWatch Logs, metrics, dashboard and alarms, with SNS e-mail (chosen)

- Pros: native to ECS, Lambda, SQS, RDS and API Gateway; SQS and RDS metrics arrive with no code; cheap at this scale.
- Cons: basic tracing; e-mail-only alerting.

### Option B: a third-party observability platform (for example Datadog or Grafana Cloud)

- Pros: richer dashboards and tracing.
- Cons: cost, agents in every task, and telemetry leaving AWS.

### Option C: add AWS X-Ray distributed tracing now

- Pros: per-request traces across services.
- Cons: more instrumentation work; deferred until a need is shown.

## Decision

1. **Logs:** structured JSON from every service, carrying a request ID across calls. Each service's logging configuration masks PII before anything is written. Logs go to CloudWatch Logs with a set retention.
2. **Metrics:** the AWS service metrics (ECS, ALB, API Gateway, SQS, RDS, Lambda) plus custom metrics for ingestion counts, audit lag and chain verification.
3. **Dashboard:** one CloudWatch dashboard for the stack.
4. **Alarms to SNS (e-mail):**
   - 5xx errors and latency;
   - audit dead-letter queue depth > 0;
   - age of the oldest audit message;
   - `AUDIT_SEND_FAILED` (metric filter);
   - ingestion failure;
   - chain mismatch;
   - AWS Budgets thresholds (50, 70, 85, 95 and 100% of USD 300, plus a Bedrock alert; superseded by [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md) decision 7, if accepted: USD 200 at 10, 20, 40, 60, 80 and 100%, plus the Bedrock alert).
5. **Security monitoring:** GuardDuty (core detection) and one CloudTrail management-events trail.
6. **Alarm owners:** every alarm names an owner and a backup who subscribe to its SNS topic and follow a runbook. The owners are not yet assigned; the auditor alarms naturally belong to the auditor's feature owner.

## Rationale

CloudWatch covers NFR 7 with no extra infrastructure, and SQS and RDS publish the key metrics automatically. Naming alarm owners turns "an operator replays the event" into a real responsibility.

## Consequences

### Positive

- Every failure path in the design raises an alarm someone receives.
- Logs never carry client personal data.

### Negative / Trade-offs

- No distributed tracing; request IDs in logs are the cross-service link.
- E-mail alerting is slow for after-hours issues. This is acceptable for the project.
- Alarm owners and runbooks must exist before the alarms are useful.

## Security & Privacy Impact

PII masking in log configuration; log groups encrypted. GuardDuty and CloudTrail cover threat detection and changes to AWS resources, including the audit anchor bucket and audit-db.

## Cost Impact

Included in the USD 23–37/month line for managed services, including GuardDuty (after its 30-day trial) and one CloudTrail trail ([environments and cost](../cost/budget.md)).

## Operational Impact

Runbooks are needed:
- dead-letter queue redrive (`docs/runbooks/audit-dlq-redrive.md`);
- `AUDIT_SEND_FAILED` replay, using a dedicated replay IAM role that [open item 18](../architecture/open-items.md#18-audit-sender-verification) must allow;
- chain-mismatch investigation;
- ingestion failure.

Alarm ownership goes in `docs/observability/monitoring-alerting.md`.

## Validation

The dead-letter-queue replay test and the auditor outage test raise and clear their alarms. A chain-mismatch test raises its alarm. A log scan shows no PII.

## References

- [NFR mapping: NFR 7](../architecture/nfr-mapping.md)
- [Security, operations and governance services](../architecture/system-overview.md#security-operations-and-governance)
- [Test strategy](../testing/test-strategy.md)
