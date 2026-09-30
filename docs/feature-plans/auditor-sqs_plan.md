# Auditor Service: Kafka to SQS Migration — Feature Plan

| Field | Value |
|---|---|
| Status | Planned |
| Feature owner | Hayyun |
| Approved by | Joseph (engineering lead), 2026-09-24 |
| Feature branch | `feat/auditor-sqs` |
| Related ADR | [ADR-0004](../adr/ADR-0004-auditor-kafka-to-sqs-migration.md) |

## Problem Statement

The auditor service consumes audit events from Kafka (`audit.insert` topic)
and publishes rejected events to an informal DLQ topic (`dql.audit`), using
`segmentio/kafka-go`. This requires operating a Kafka broker and contradicts
`docs/architecture/nfr-notes.md`, which otherwise states the system has no
message broker. Joseph has requested migrating this to AWS SQS.

## User/Business Value

Removes the operational and cost overhead of running a Kafka cluster for
what is currently a single producer-relationship/single-consumer flow,
replacing it with a managed, IAM-integrated, pay-per-use queue. Also fixes
several reliability bugs in the current Kafka handling (see ADR-0004
Consequences).

## Scope

- Replace the auditor's Kafka reader with an SQS consumer (long polling,
  delete-on-success).
- Replace the auditor's Kafka DLQ writer with an SQS DLQ sender.
- Add `event_id`-based idempotency (new column + de-dup check).
- Replace Kafka config/env vars with SQS config/env vars.
- Remove Kafka dependency (`segmentio/kafka-go`); add AWS SDK
  (`aws-sdk-go-v2`, `service/sqs`).
- Update local Compose to use LocalStack instead of Kafka/kafka-ui.
- Update `services/auditor/README.md` and `docs/architecture/nfr-notes.md`.
- Add/update unit tests for the new consumer and DLQ sender.

## Explicit Non-Scope

- No other service currently produces audit events; this plan does not
  implement a producer in the CRM, user-management, or transaction
  services. It only requires that any future producer include `event_id`.
- Does not resolve the separate, still-open `event-driven-alerts` feature
  plan (owned by Azeez) or choose a broker for *future* alerting features.
- Does not provision the AWS SQS queues themselves — see Deployment/
  Migration Plan below for what must be requested from Joseph's team.
- Does not change the auditor's gRPC API or health endpoint. A later
  user-directed extension adds HTTP CRUD endpoints for corrupted audit logs;
  those endpoints do not alter the health or gRPC contracts.

## Acceptance Criteria

- Auditor consumes from an SQS queue instead of Kafka; messages are only
  deleted after successful processing or after being routed to the DLQ.
- A message that fails to persist (DB error) is left in the queue for retry,
  not silently dropped.
- Malformed/unauthorized payloads are sent to the DLQ with a reason.
- A message redelivered with a previously-processed `event_id` is skipped,
  not written twice.
- No AWS credentials are hardcoded; all config is via environment variables.
- Existing payload validation tests continue to pass unchanged.
- `docker compose up` works locally against LocalStack without a real AWS
  account.

## Functional Requirements

- Long-poll SQS (`WaitTimeSeconds=20`), batch receive up to 10 messages.
- Process each message: validate payload, de-dup on `event_id`, write to
  the audit chain, then delete the message.
- On DB write failure: do not delete the message (allow SQS redelivery).
- On payload validation failure: send to DLQ with a reason, then delete
  from the main queue.
- Rely on the queue's configured redrive policy for messages that fail
  processing repeatedly without an explicit validation error.

## Relevant Non-Functional Requirements

- **Availability**: consumer must not crash-loop or stall permanently on a
  single bad message (fixes the current DLQ-writer-stalls-forever bug).
- **Maintainability**: SQS client wrapped behind a small interface so tests
  don't depend on real AWS/LocalStack.
- **Security**: IAM least-privilege per service; SSE-SQS encryption at
  rest; no hardcoded credentials.

## Proposed APIs/Events/Jobs

- The SQS migration adds no API. Its `audit.insert` message contract gains a
  required `event_id` (string, unique per event) field for de-duplication.
- Corrupted audit log management is exposed over HTTP:
  `GET /api/corrupted-logs`, `POST /api/corrupted-logs`, and
  `DELETE /api/corrupted-logs/{id}`. The POST body contains a UUID `id` and
  the original audit `payload`; detection time is assigned by the server.

## Proposed Data Model/Schema Changes

- Add a unique `event_id` column to `audit_log_table.sql` and
  `test_init.sql` (auditor's own audit log table) to support
  idempotent writes.
- Persist corrupted audit log records in `corrupted_audit_log`, with the
  original log payload and server-assigned detection timestamp.

## Authorization/Access Rules

- IAM policy for the auditor: `sqs:ReceiveMessage`, `DeleteMessage`,
  `ChangeMessageVisibility`, `GetQueueAttributes` on `audit-insert`;
  `sqs:SendMessage` on `audit-insert-dlq`.
- IAM policy for any future producer service: `sqs:SendMessage` on
  `audit-insert` only.

## Audit Logging Requirements

Unaffected — this change is internal to how the auditor receives events, not
what it logs. The audit chain's contents and format are unchanged.

## Error/Failure Behavior

- DB write failure → message stays in queue, retried per the queue's
  visibility timeout, eventually redriven to the DLQ if failures persist.
- Payload validation failure → explicit DLQ send with reason, message
  deleted from the main queue.
- Duplicate `event_id` → skipped, message deleted (already handled).

## Observability Requirements

- Log each DLQ send with the reason and message ID (already present for
  Kafka; carried over).
- Log de-duplication skips distinctly from successful processing, so
  redelivery volume is visible.

## Cost Impact

Pay-per-request SQS pricing; expected volume is low. Net cost decrease
versus operating a Kafka cluster (see ADR-0004 Cost Impact).

## Security/Privacy Impact

See ADR-0004 Security & Privacy Impact. No new PII is introduced; the
message payload shape is unchanged aside from `event_id`.

## Test Plan

- Unit tests with a mocked/fake SQS client:
  - message deleted after successful processing
  - message NOT deleted when DB write fails
  - malformed/unauthorized payload sent to DLQ and deleted from main queue
  - duplicate `event_id` skipped, not double-written
- Existing `payload_unmarshalling_test.go` cases carried over unchanged
  (only the package path changes).
- Manual local validation against LocalStack: send a message, confirm it is
  processed and removed from the queue.

## Deployment/Migration Plan

1. Request from Joseph's team: creation of `audit-insert` (standard queue,
   visibility timeout ~60s, receive wait time 20s, retention 4–14 days,
   redrive to DLQ after 5 failures, SSE-SQS enabled) and
   `audit-insert-dlq` (retention 14 days, SSE-SQS enabled), plus IAM
   policies as described above.
2. Deploy the updated auditor pointed at the new queues via env vars
   (`AUDIT_QUEUE_URL`, `AUDIT_DLQ_URL`, `AWS_REGION`).
3. Since no producer exists yet in this repo, there is no cutover
   coordination needed with another service at this time.

## Rollback Plan

Revert to the previous auditor image/commit (Kafka-based) and restore the
previous `KAFKABROKERS`/`HANDLER_IP` env vars. Record the reversal as a new
ADR that supersedes ADR-0004, per the append-only ADR policy.

## Open Questions

- None blocking — ordering (not required) and idempotency approach
  (`event_id`) were confirmed by Joseph on 2026-09-24. Exact AWS queue
  provisioning owner/timeline still to be confirmed with Joseph's team.

## ADRs Required Before Implementation

- [ADR-0004](../adr/ADR-0004-auditor-kafka-to-sqs-migration.md) — Accepted.

## Task Breakdown

| Task ID | Task | Primary Dev | Collaborators | Files/Components | Branch | Dependencies/Conflict Zones | Status | Last Sync |
|---|---|---|---|---|---|---|---|---|
| AS-T01 | Migrate auditor Kafka reader/writer to SQS consumer/DLQ sender, add `event_id` idempotency, update deps/config/compose/docs/tests | Hayyun | Joseph (review) | `services/auditor/src/serverd/**`, `services/auditor/data/*.sql`, `services/auditor/README.md`, `services/auditor/go.mod`, `compose.yaml`, `docker-compose.yaml`, `docs/architecture/nfr-notes.md` | `task/auditor-sqs/kafka-migration` | None currently — no other service touches auditor's Kafka/SQS code or these files | Planned | 2026-09-24 |

This is scoped as a single task rather than split across multiple
developers/branches: the messaging code, schema change, and config/docs
updates are tightly coupled and not sensibly separable without leaving the
service in an inconsistent intermediate state.

## Dependencies/Conflict Zones Between Developers

None identified. No other active branch touches
`services/auditor/src/serverd/kafka/` or the auditor's Compose/env
configuration (checked against `origin/feat/event-driven-alerts` and
`origin/task/event-driven-alerts/plan`, which reference the same files but
have not been merged and are a separate, still-open decision — see
ADR-0004 Context).

## Last Synchronization/Update Timestamp Per Active Task

- AS-T01: 2026-09-24, synchronized against `origin/dev` at `544ff66`.
