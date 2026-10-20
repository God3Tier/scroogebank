# Event-Driven Alerts Operations Runbook

Status: offline operational baseline. Cloud commands, screenshots, alarm tests,
and deployment evidence remain pending approved AWS access.

## Safety invariants

- Both outbound capabilities default off.
- Quota or budget exhaustion fails closed; it does not silently downgrade a
  reservation.
- Re-enable requires a named human actor, correlation ID, and successful audit.
- A capability disabled by the current month's budget cannot be re-enabled until
  the UTC month rolls over or the approved budget configuration changes.
- Do not use real client data or credentials in tests, logs, metrics, or drills.

## Configuration

| Environment variable | Default | Meaning |
|---|---:|---|
| `ALERT_MODE` | `API` | `API` serves `/api/alerts`; `WORKER` runs one ingestion window and exits, with code 1 if it failed |
| `ALERT_INGESTION_ENABLED` | `false` | Allows provider ingestion reservations |
| `ALERT_AI_ENABLED` | `false` | Allows model invocation and model-token reservations |
| `ALERT_MONTHLY_SEARCH_QUOTA` | `1000` | Search reservations per UTC month |
| `ALERT_MAX_SEARCHES_PER_RUN` | `4` | Search reservations per worker run |
| `ALERT_MINIMUM_SEARCH_CLIENT_SHARE` | `0.05` | Minimum fraction of distinct snapshot clients before an instrument's public terms are searchable |
| `ALERT_MINIMUM_SEARCH_CLIENT_COUNT` | `2` | Minimum distinct snapshot clients before an instrument's public terms are searchable |
| `ALERT_MONTHLY_MODEL_TOKEN_QUOTA` | `200000` | Model-token reservations per UTC month |
| `ALERT_MAX_MODEL_TOKENS_PER_INVOCATION` | `2000` | Tokens per model reservation |
| `ALERT_MONTHLY_BUDGET_USD` | `25.00` | Estimated-cost stop threshold |
| `ALERT_AUDIT_PUBLISHER_ENABLED` | `false` | Enables leased delivery to the auditor SQS queue |
| `AUDIT_QUEUE_URL` | empty | Required SQS queue URL when audit publishing is enabled |
| `AWS_REGION` | `ap-southeast-1` | Region of the auditor SQS queue |
| `AWS_ENDPOINT_URL` | empty | LocalStack endpoint for local integration only; omit in AWS |
| `ALERT_AUDIT_PUBLISH_MAX_ATTEMPTS` | `10` | Maximum delivery claims before an event remains failed for investigation |
| `ALERT_RETENTION_ENABLED` | `true` | Enables bounded local content expiry |

There is intentionally no unauthenticated operator endpoint in this offline
slice. Production controls must be exposed only through an authenticated,
authorized adapter. Re-enable writes a redacted event to the PostgreSQL audit
outbox before changing the control state. An unavailable or full outbox
fails closed and leaves the capability disabled.

## Inspect health and consumption

Inspect application health and these low-cardinality Micrometer series:

- `alert.outbound.reservations`, tagged by `capability` and `result`
- `alert.outbound.disabled`, tagged by `capability` and `reason`
- `alert.outbound.reenabled`, tagged by `capability`
- `alert.outbound.monthly.searches`
- `alert.outbound.monthly.model.tokens`
- `alert.outbound.monthly.estimated.cost.usd`

Correlate application evidence with the provider bill before treating estimated
cost as authoritative.

## Disable and contain

1. Identify the incident, actor, affected capability, and correlation ID.
2. Disable ingestion and/or model operation through the approved operator
   adapter. If that adapter is unavailable, set the relevant enable flag to
   `false` and redeploy through the normal reviewed path.
3. Confirm new reservations are denied and the disabled counter increments.
4. Preserve redacted logs, metrics, provider usage, and billing evidence.
5. Do not re-enable while spend, malformed evidence, authorization, or provider
   availability remains uncertain.

## Provider outage or malformed response

1. Stop new reservations for the affected capability.
2. Confirm failures are visible without recording unverified evidence or
   generating alerts from incomplete data.
3. Record the provider status, first/last failure, affected run IDs, and retry
   count using synthetic or redacted identifiers.
4. Resume only after a bounded synthetic probe succeeds and the human owner
   approves an audited re-enable.

## Replay and recovery

Bounded historical ingestion replay is implemented behind the internal worker
boundary. It does not move the scheduler watermark. Before a production replay:

1. Select an explicit bounded run/event range and verify database idempotency.
2. Keep outbound capabilities off while checking the candidate set.
3. Estimate searches, tokens, and cost; stop if any remaining quota is
   insufficient.
4. Obtain owner approval and record its correlation ID.
5. Enable only the required capability, execute the bounded replay, and disable
   it again if the drill requires containment.
6. Confirm duplicate deliveries did not create duplicate alerts or audit events.

For local auditor recovery, inspect `audit_outbox` by status and attempt count.
`PENDING` and `FAILED` rows below the attempt cap are reclaimed in creation order;
an expired `LEASED` row is also safe to reclaim. Confirm SQS and the auditor are
healthy before enabling the publisher. Never edit payloads or mark rows delivered
manually; delivery state changes only after broker acknowledgement.

The hourly retention job processes a bounded batch. It removes expired briefing
text and redacts expired source metadata, but never deletes audit evidence. A
backlog is safe to drain over repeated runs; increase the batch only after checking
database load.

## Re-enable checklist

- Root cause is understood and corrected.
- Remaining monthly quota and budget are independently verified.
- A synthetic bounded probe passed.
- Required ADRs are accepted and the durable audit adapter is healthy.
- The owner, reason, and correlation ID are recorded before state changes.
- Dashboards and alarms are observed through at least one scheduled run.

## Current gaps

CloudWatch/IaC, billing reconciliation, authenticated operator controls, live
provider integration, staging replay execution, and cloud operational-drill
evidence are not implemented. Local auditor publication, bounded retry/recovery,
and retention are implemented and tested, but no production broker or cloud
control was exercised. This document must not be used as evidence that cloud
controls were tested.

The monthly search and token counts, the estimated cost and every disable are
kept in alerts-db (`outbound_usage` by UTC month, `outbound_control` by
capability), so the API and each worker run share them. Reservations are atomic
statements, so concurrent processes cannot exceed a quota, and a reservation is
kept even if the caller's own transaction later rolls back. An operator disable
or a quota or budget stop made in one process therefore applies to the next worker
run. Configuration still wins: a capability switched off by its enable flag stays
off, whatever is stored.

Each run holds a PostgreSQL session advisory lock for its whole duration. A
second run, scheduled, manual or a replay, ends as `ALREADY_RUNNING` before it
reserves quota or searches. The lock belongs to one database connection, so a
crashed process releases it when its connection closes.
