# ADR-0020: Audit writes through a per-service outbox and one shared SQS standard queue

- Status: Accepted
- Decision date/time: 2026-10-09 14:56 +08:00 (Asia/Singapore); first proposed 2026-10-01 21:50 +08:00
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture` (proposed); `task/base-features/audit-outbox` (amended, accepted, first implementation in user-service)
- Related issue/PR: TBD (pull request from `task/base-features/audit-outbox` to `dev`)
- Supersedes: N/A (extends [ADR-0004](ADR-0004-auditor-kafka-to-sqs-migration.md))
- Superseded by: N/A

## Context

Every business action in Features 1–6 must produce an audit record, and the audit log must be tamper-resistant. [ADR-0004](ADR-0004-auditor-kafka-to-sqs-migration.md) (Accepted, owner Hayyun) moved the auditor from Kafka to Amazon SQS: one standard queue `audit-insert`, a dead-letter queue `audit-insert-dlq` after 5 receives, and de-duplication by `event_id`. It covers the auditor side. This ADR decides how **producers** get their events onto that queue so that no business change is ever left without its audit record, including producers with no database (Cognito triggers, dashboard-service) and the ingest Lambda.

By 2026-10-09 every request-driven producer on `dev` (user-service, client-profile-management-service and account-service) builds its audit events in HTTP middleware that runs after the request, once the business transaction has committed, and sends each event straight to SQS. An SQS error or outage therefore loses events. The events carry HTTP details (endpoint, method, status code) that the auditor requires, and they cover reads and failed attempts as well as changes.

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

### Option C: transactional outbox: the outbox row commits in the business transaction (chosen: the default)

- Pros: the business row and the outbox row commit in one transaction, so an event exists exactly when the change does; SQS buffers outages; one queue and one consumer to run.
- Cons: each service needs an outbox table and a relay loop; the audit log lags by seconds; the shared queue does not prove the sender. For the producers on `dev`, event creation would have to move from the request middleware into service code; the service layer does not know the HTTP details the auditor's event format requires; and reads, failed attempts and logouts have no business transaction to join, so they would need a separate path.

### Option D: one queue per producer

- Pros: burst and failure isolation; queue permissions prove the sender.
- Cons: a queue, dead-letter queue, alarm set and polling loop for each of more than eight producers.

### Option E: Kafka (Amazon MSK) or FIFO queues

- Pros: Kafka offers replay and many consumer groups; FIFO offers ordering.
- Cons: MSK costs about USD 80–95/month and cannot be paused; ordering is not needed because the auditor fixes chain order on insert; FIFO costs more and caps throughput. Already rejected in ADR-0004.

### Option F: outbox row saved in its own transaction right after the request (chosen: an exception, because of the cost of change)

- Pros: SQS and auditor outages no longer lose events; events stay where producers already build them, so the event format, the auditor and existing tests are unchanged; reads, failed attempts and logouts stay audited; about a fifth of the work of Option C per producer.
- Cons: the business change and the outbox row are two commits, so a crash between them loses that event; each service still needs an outbox table and a relay loop; the audit log lags by seconds.

## Decision

1. **Services with a database** keep an outbox table in their own database. A relay loop inside the service sends unsent rows to `audit-insert` every 2 seconds and deletes each row once SQS accepts it.
   - **Default (Option C):** the outbox row is written in the **same database transaction** as the business change it describes, so the event exists exactly when the change does. New producers use the default.
   - **Exception (Option F):** a producer that already builds its audit events in request middleware, after the business transaction has committed, may instead save the row **in its own short transaction immediately after the request**. The exception exists only because of the cost of changing those producers: user-service, client-profile-management-service and account-service work this way on `dev`, and moving their event creation into service code would mean a new event format, a separate path for reads, failed attempts and logouts, and reworked tests (see Option C). If saving the row fails, the event is logged and dropped; the request is not affected.
2. **One shared SQS standard queue** (`audit-insert`) for all producers, with `audit-insert-dlq` after 5 failed receives, as in ADR-0004. The auditor deletes a message only after its insert commits and skips duplicate `event_id`s.
3. **transaction-ingest Lambda:** writes its `FILE_PROCESSED` events to its own `ingest_outbox` table in transactions-db, in the same transaction as the import, and relays unsent rows at the end of each run. Unsent rows wait for the next run (about 5 minutes later).
4. **Cognito triggers** (`PostAuthentication`, `PostConfirmation_ConfirmForgotPassword`) have no database. They send directly and **fail open**: retry briefly within Cognito's ~5-second trigger limit, then write the full event to CloudWatch Logs as `AUDIT_SEND_FAILED`. A metric filter raises an alarm, and an operator replays the event. The login or password reset still succeeds.
5. **dashboard-service** has no database. It sends `DASHBOARD_VIEW` events directly, best-effort; a failed audit send never fails the read.
6. The auditor scales on queue backlog. Alarms fire on dead-letter queue depth and on the age of the oldest message ([ADR-0027](ADR-0027-observability-baseline.md)).
7. **Sender verification is open** ([open item 18](../architecture/open-items.md#18-audit-sender-verification)): the auditor may request the SQS `SenderId` attribute and reject events whose sending IAM role does not match `source_service`.

## Rationale

An outbox is the only option that keeps business writes independent of the auditor and of SQS without losing events when either is down, and writing the row in the business transaction (Option C) is the only way to guarantee that an event exists exactly when the change does (NFR 4). Option C is therefore the default.

Option F is allowed only because of the cost of change. The producers on `dev` build their events in request middleware after the commit, and moving that into service code in every one of them needs a different event format, a separate path for reads, failed attempts and logouts, and reworked tests. For those producers, saving the row right after the request still removes the failure the team is likely to hit, SQS being unavailable, for about a fifth of the work, and leaves the event format, the auditor and the existing tests as they are. What it gives up is a gap of a few milliseconds between the business commit and the outbox write, which loses an event only if the process crashes at exactly that moment. A producer can move to the default later without changing the relay or the queue.

One shared queue keeps operations simple at this volume, matches what the auditor on `dev` consumes today, and costs under about USD 1/month. Failing open for sign-in events avoids an SQS outage locking every user out. A separate `ingest_outbox` keeps each component's events under its own database user and IAM role, unlike a shared outbox table with a producer column, whose separation would depend on every query's filter.

## Consequences

### Positive

- No audit event lost to an SQS or auditor outage; auditor outages and deployments do not affect business traffic.
- Producers using the exception keep auditing reads, failed attempts and logouts, because their middleware sees every request.
- One queue, one dead-letter queue, one consumer loop and one redrive procedure.

### Negative / Trade-offs

- For producers using the exception, a crash between the business commit and the outbox write loses that event, so NFR 4 ("an audit event exists exactly when the business change exists") holds for them except in that window.
- For the same producers, if saving the outbox row fails (the service's database becomes unavailable just after the commit), the event is logged and lost.
- Two ways of writing the outbox row: reviewers must check which one a service uses, and the audit log's completeness guarantee differs between them.
- The audit log is eventually consistent, typically seconds behind.
- Every service with a database maintains an outbox table and relay.
- A sign-in event can be missing from the chain until it is replayed; dashboard view events are best-effort.
- Until sender verification is added, a compromised producer could claim to be another service.
- No burst isolation: a large import can delay other services' events by seconds to minutes.

## Security & Privacy Impact

Only producer IAM roles may send to `audit-insert` (IAM plus queue policy); the queue uses SSE. Audit payloads with client personal data must be encrypted per client so crypto-shredding still works ([ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md), [open item 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data)). A manual replay of `AUDIT_SEND_FAILED` events needs a dedicated replay role, which sender verification must allow, and CloudTrail records who sent it. Outbox rows hold the same payload as the SQS message and are deleted once sent.

## Cost Impact

SQS charges per request; at this volume the shared queue stays under about USD 1/month. Outbox tables use existing databases. The relay's 2-second poll is one indexed query per task; on the provisioned `db.t4g.micro` instances ([ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md)) it adds no charge, and it stops with the service under the stop model ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)). No MSK (saving about USD 80–95/month).

## Operational Impact

- Alarms: dead-letter queue depth > 0, oldest message age, `AUDIT_SEND_FAILED` metric filter, and the age of the oldest unsent outbox row in each service, so an SQS problem is noticed before events pile up.
- Runbook needed: read the auditor logs, fix the cause, redrive the dead-letter queue (`StartMessageMoveTask` or the console), and confirm the events were chained once. An owner (the "operator") must be named for each alarm ([ADR-0027](ADR-0027-observability-baseline.md)).
- Rows saved just before a scheduled shutdown are sent when the service next starts.
- Rollout, one branch per service: user-service (`task/base-features/audit-outbox`), client-profile-management-service (`task/client-profile-management/client-audit-outbox`) and account-service (`task/client-profile-management/account-audit-outbox`) under the exception; the SFTP receiver, which acts as transaction-service and transaction-ingest, under decision 3 with `ingest_outbox` (`task/transaction-integration/ingest-outbox`).

## Validation

- **Dead-letter-queue replay test:** a malformed event moves to the dead-letter queue after 5 failed receives and raises the alarm; after the fix and redrive it is chained exactly once.
- **Auditor outage test:** stop the auditor, make changes, restart it, and check every audit event arrives.
- **SQS outage test:** point a service at an unreachable queue, make changes, restore the queue, and check every event arrives exactly once.
- Tests for each relay against Postgres (Testcontainers), with SQS mocked or on LocalStack.

## Revision history

- 2026-10-01 21:50 +08:00: proposed, with decision 1 as Option C (the outbox row commits in the business transaction).
- 2026-10-09 14:56 +08:00: amended before acceptance, then accepted by the decision owner. Decision 1 keeps Option C (the outbox row commits in the business transaction) as the default and adds Option F (the row is saved in its own transaction right after the request) as an exception for producers that already build their events in request middleware, chosen because of the cost of changing them; see Rationale. Decisions 2–7 are unchanged.

## References

- [Audit write path](../architecture/data-flow.md#audit-write-path-sqs-not-grpc) and its sub-sections
- [Authentication events](../security/security-model.md#authentication-events)
- [Transaction ingestion: audit delivery](../architecture/data-flow.md#transaction-ingestion-feature-3)
- [ADR-0004](ADR-0004-auditor-kafka-to-sqs-migration.md) (Accepted; extended, not superseded)
- [Open item 18](../architecture/open-items.md#18-audit-sender-verification)
- [`docs/feature-plans/auditor-sqs_plan.md`](../feature-plans/auditor-sqs_plan.md)
