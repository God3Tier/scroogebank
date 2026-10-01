# ADR-0020: Audit writes through a transactional outbox and one shared SQS standard queue

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A (extends [ADR-0004](ADR-0004-auditor-kafka-to-sqs-migration.md))
- Superseded by: N/A

## Context

Every business action in Features 1–6 must produce an audit record, and the audit log must be tamper-resistant. [ADR-0004](ADR-0004-auditor-kafka-to-sqs-migration.md) (Accepted, owner Hayyun) moved the auditor from Kafka to Amazon SQS: one standard queue `audit-insert`, a dead-letter queue `audit-insert-dlq` after 5 receives, and de-duplication by `event_id`. It covers the auditor side. This ADR decides how **producers** get their events onto that queue so that no business change is ever left without its audit record, including producers with no database (Cognito triggers, dashboard-service) and the ingest Lambda.

## Decision Drivers

- Consistency: an audit event exists exactly when the business change exists (NFR 4)
- Availability: business writes must not depend on the auditor being up (NFR 2)
- Durability: no audit event lost (NFR 9)
- Resiliency and graceful failure (NFR 5)
- Simplicity and cost

## Options Considered

### Option A: synchronous gRPC write to the auditor

- Pros: simple; the audit record is written before the response.
- Cons: an auditor outage blocks every business write; the business commit and the audit call can disagree if either fails; every request waits behind the hash-chain insert lock.

### Option B: send to SQS directly after the business commit

- Pros: no outbox table.
- Cons: a crash or SQS error between the commit and the send loses the event.

### Option C: transactional outbox per service, relayed to one shared SQS standard queue (chosen)

- Pros: the business row and the outbox row commit in one transaction; SQS buffers outages; one queue and one consumer to run.
- Cons: each service needs an outbox table and a relay loop; the audit log lags by seconds; the shared queue does not prove the sender.

### Option D: one queue per producer

- Pros: burst and failure isolation; queue permissions prove the sender.
- Cons: a queue, dead-letter queue, alarm set and polling loop for each of more than eight producers.

### Option E: Kafka (Amazon MSK) or FIFO queues

- Pros: Kafka offers replay and many consumer groups; FIFO offers ordering.
- Cons: MSK costs about USD 80–95/month and cannot be paused; ordering is not needed because the auditor fixes chain order on insert; FIFO costs more and caps throughput. Already rejected in ADR-0004.

## Decision

1. **Services with a database** write the business change and an outbox row (`event_id`, `source_service`, actor, before/after values) in **one database transaction**. A relay loop inside the service sends unsent rows to `audit-insert` every few seconds and marks them sent.
2. **One shared SQS standard queue** (`audit-insert`) for all producers, with `audit-insert-dlq` after 5 failed receives, as in ADR-0004. The auditor deletes a message only after its insert commits and skips duplicate `event_id`s.
3. **transaction-ingest Lambda:** writes its `FILE_PROCESSED` events to its own `ingest_outbox` table in transactions-db, in the same transaction as the import, and relays unsent rows at the end of each run. Unsent rows wait for the next run (about 5 minutes later).
4. **Cognito triggers** (`PostAuthentication`, `PostConfirmation_ConfirmForgotPassword`) have no database. They send directly and **fail open**: retry briefly within Cognito's ~5-second trigger limit, then write the full event to CloudWatch Logs as `AUDIT_SEND_FAILED`. A metric filter raises an alarm, and an operator replays the event. The login or password reset still succeeds.
5. **dashboard-service** has no database. It sends `DASHBOARD_VIEW` events directly, best-effort; a failed audit send never fails the read.
6. The auditor scales on queue backlog. Alarms fire on dead-letter queue depth and on the age of the oldest message ([ADR-0027](ADR-0027-observability-baseline.md)).
7. **Sender verification is open** ([open item 18](../architecture/open-items.md#18-audit-sender-verification)): the auditor may request the SQS `SenderId` attribute and reject events whose sending IAM role does not match `source_service`.

## Rationale

The outbox is the only option that guarantees "business change ⇔ audit event" without making business writes depend on the auditor. One shared queue keeps operations simple at this volume, matches what the auditor on `dev` consumes today, and costs under about USD 1/month. Failing open for sign-in events avoids an SQS outage locking every user out. A separate `ingest_outbox` keeps each component's events under its own database user and IAM role, unlike a shared outbox table with a producer column, whose separation would depend on every query's filter.

## Consequences

### Positive

- No audit event lost for any business change; auditor outages and deployments do not affect business traffic.
- One queue, one dead-letter queue, one consumer loop and one redrive procedure.

### Negative / Trade-offs

- The audit log is eventually consistent, typically seconds behind.
- Every service with a database maintains an outbox table and relay.
- A sign-in event can be missing from the chain until it is replayed; dashboard view events are best-effort.
- Until sender verification is added, a compromised producer could claim to be another service.
- No burst isolation: a large import can delay other services' events by seconds to minutes.

## Security & Privacy Impact

Only producer IAM roles may send to `audit-insert` (IAM plus queue policy); the queue uses SSE. Audit payloads with client personal data must be encrypted per client so crypto-shredding still works ([ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md), [open item 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data)). A manual replay of `AUDIT_SEND_FAILED` events needs a dedicated replay role, which sender verification must allow, and CloudTrail records who sent it.

## Cost Impact

SQS charges per request; at this volume the shared queue stays under about USD 1/month. Outbox tables use existing databases. No MSK (saving about USD 80–95/month).

## Operational Impact

- Alarms: dead-letter queue depth > 0, oldest message age, `AUDIT_SEND_FAILED` metric filter.
- Runbook needed: read the auditor logs, fix the cause, redrive the dead-letter queue (`StartMessageMoveTask` or the console), and confirm the events were chained once. An owner (the "operator") must be named for each alarm ([ADR-0027](ADR-0027-observability-baseline.md)).

## Validation

- **Dead-letter-queue replay test:** a malformed event moves to the dead-letter queue after 5 failed receives and raises the alarm; after the fix and redrive it is chained exactly once.
- **Auditor outage test:** stop the auditor, make changes, restart it, and check every audit event arrives.
- Testcontainers tests with Postgres and LocalStack SQS for each relay.

## References

- [Audit write path](../architecture/data-flow.md#audit-write-path-sqs-not-grpc) and its sub-sections
- [Authentication events](../security/security-model.md#authentication-events)
- [Transaction ingestion: audit delivery](../architecture/data-flow.md#transaction-ingestion-feature-3)
- [ADR-0004](ADR-0004-auditor-kafka-to-sqs-migration.md) (Accepted; extended, not superseded)
- [Open item 18](../architecture/open-items.md#18-audit-sender-verification)
- [`docs/feature-plans/auditor-sqs_plan.md`](../feature-plans/auditor-sqs_plan.md)
