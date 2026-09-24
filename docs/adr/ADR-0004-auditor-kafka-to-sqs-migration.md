# ADR-0004: Migrate Auditor Service Messaging from Kafka to Amazon SQS

- Status: Accepted
- Decision date/time: 2026-09-24 15:00 Asia/Singapore *(confirm/adjust exact time before commit)*
- Decision owner(s): Hayyun
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: feat/auditor-sqs
- Related issue/PR: TBD — update once the PR is opened
- Supersedes: N/A
- Superseded by: N/A

## Context

The auditor service (`services/auditor`) currently uses Apache Kafka
(`segmentio/kafka-go`) for its messaging:

- **`audit.insert`** — the auditor consumes from this topic as consumer group
  `audit-service`. No producer currently exists in this repository; the CRM,
  user-management, and transaction services are expected to publish audit
  events here once built.
- **`dql.audit`** — the auditor publishes rejected/invalid events here as an
  informal dead-letter topic. Nothing currently consumes it.

This contradicts `docs/architecture/nfr-notes.md`, which states "no service
consumes events from another, so there is no Kafka/RabbitMQ/SQS" — that note
was already inaccurate before this ADR, since the Kafka code and local Compose
service predate it.

Joseph (engineering lead) requested migrating the auditor's messaging from
Kafka to AWS SQS, with no delivery deadline. This ADR covers only the auditor
service's existing Kafka usage. It does not resolve the separate, still-open
`event-driven-alerts` feature plan (owned by Azeez), which leaves the
broker/mechanism choice for *future* event-driven alerting undecided; Azeez
has been made aware of this migration so the two efforts don't make
conflicting assumptions about the auditor's messaging layer.

## Decision Drivers

- AWS-native, fully managed service — no broker cluster to operate or patch
- Cost: pay-per-request, no standing Kafka cluster cost
- Operational simplicity: a single producer group / single consumer does not
  need Kafka's partition/consumer-group machinery
- IAM-based access control instead of an open broker port
- Consistency with the architecture `nfr-notes.md` already describes
- Easier local development/testing via LocalStack, without deploying a
  Kafka+Zookeeper/KRaft stack for developers

## Options Considered

### Option A — Keep Kafka
Pros: no migration effort; retains Kafka's log-based replay.
Cons: contradicts the documented "no broker" architecture; requires
operating (or paying for a managed) Kafka cluster for what is currently a
single producer/single consumer relationship; higher operational surface
than the traffic justifies.

### Option B — Migrate to Amazon SQS (standard queue) — Chosen
Pros: fully managed, IAM-integrated, pay-per-use, no cluster to run;
sufficient for the current single-producer/single-consumer `audit.insert`
flow; ordering is not required (see Decision below).
Cons: no built-in message replay after deletion; at-least-once delivery
requires idempotent handling on the consumer side.

### Option C — Amazon EventBridge / SNS
Not pursued. SQS was specifically requested and directly fits the existing
point-to-point producer/consumer shape already implemented; EventBridge/SNS
fan-out patterns are not needed here.

## Decision

Replace the auditor's Kafka reader/writer with Amazon SQS:

- **`audit-insert`** (standard, non-FIFO queue) replaces the `audit.insert`
  topic. Producers call `SendMessage`; the auditor long-polls
  (`WaitTimeSeconds=20`), processes, and deletes each message after
  successful handling.
- **`audit-insert-dlq`** replaces `dql.audit`. It serves two roles: SQS's
  configured redrive target after repeated processing failures, and the
  direct destination the auditor writes to for events that fail payload
  validation.
- Ordering is not required: audit events carry a timestamp, and per the
  confirmed decision (2026-09-24), delivery order does not need to match
  event order. A standard (non-FIFO) queue is therefore sufficient; FIFO is
  not used.
- Every published event includes a new `event_id` field. The auditor
  de-duplicates on this field before writing to the audit chain, since SQS
  standard queues deliver at-least-once and could redeliver a message the
  auditor already processed. This is a message-contract change: every future
  producer (CRM, user-management, transaction services) must populate
  `event_id`.

## Rationale

The existing Kafka usage is a single producer-group-to-single-consumer
relationship with no evidence of a need for partitioned ordering, multiple
independent consumer groups, or log replay — none of the reasons that
usually justify Kafka over a managed queue apply here. SQS meets the actual
requirement (reliable at-least-once delivery with a dead-letter path) with
less operational surface, and brings the code in line with the "no
self-hosted broker" intent already recorded in `nfr-notes.md`.

## Consequences

### Positive
- No Kafka cluster to provision, operate, patch, or pay for.
- IAM-scoped access replaces an open broker port and plaintext listener
  config.
- Local development can use LocalStack instead of a multi-container Kafka
  stack.
- Fixes several latent bugs in the Kafka implementation along the way: audit
  entries silently lost on DB write failure, the DLQ writer permanently
  stalling after one failed write, and a busy-retry loop on shutdown.

### Negative / Trade-offs
- **No replay.** Once an SQS message is deleted, it cannot be re-read the
  way a Kafka consumer can rewind an offset. This has no functional impact
  on the auditor today: audit entries are persisted to Postgres immediately
  on receipt, and the cronjob's periodic chain verification
  (`ReadOffset`/`VerifyHistoricalChain`) operates against the database, not
  against the message queue.
- **At-least-once delivery** requires idempotent handling — mitigated by the
  `event_id` de-duplication described above.
- **Message contract change.** Any service that later produces audit events
  must include `event_id`; this is a new requirement beyond the existing
  `ValidEvent` fields.

## Security & Privacy Impact

- IAM policies scoped to the specific queue ARNs (`audit-insert`,
  `audit-insert-dlq`), least privilege per service (producers get
  `SendMessage` only; the auditor gets `ReceiveMessage`, `DeleteMessage`,
  `ChangeMessageVisibility`, `GetQueueAttributes` on the main queue and
  `SendMessage` on the DLQ).
- Server-side encryption at rest (SSE-SQS) enabled on both queues.
- No credentials hardcoded; AWS SDK default credential chain (IAM role in
  AWS, environment variables locally).

## Cost Impact

Pay-per-request SQS pricing; audit event volume is low and comfortably
within typical free-tier limits. This removes the cost of operating a
dedicated Kafka cluster (or a managed Kafka service) that the previous setup
would have required in AWS.

## Operational Impact

- Local Compose (`compose.yaml`) replaces the `kafka`/`kafka-ui` services
  with LocalStack, with an init script creating both queues.
- `docker-compose.yaml`'s commented-out Kafka block is removed.
- New environment variables: `AWS_REGION`, `AUDIT_QUEUE_URL`,
  `AUDIT_DLQ_URL`; `AWS_ENDPOINT_URL` for local/LocalStack use only. Removed:
  `KAFKABROKERS`, `HANDLER_IP`.
- **AWS provisioning required before deployment** (requested from Joseph's
  team, owner TBD): `audit-insert` and `audit-insert-dlq` queues, with
  visibility timeout, retention, redrive policy (max 5 receives before
  redrive to the DLQ), and SSE-SQS enabled.
- Rollback: revert the auditor to the previous commit/image using Kafka, and
  mark this ADR's status `Superseded` by a follow-up ADR recording the
  reversal and its reason.

## Validation

- Unit tests against a fake/mocked SQS client covering: message deleted only
  after successful processing; message left in-queue (not deleted) when the
  database write fails; malformed/unauthorized payloads routed to the DLQ;
  duplicate `event_id` skipped without a second write.
- Manual local validation against LocalStack: send a test message, confirm
  it is processed, persisted, and deleted from the queue.

## References

- AWS SQS developer guide: long polling, visibility timeout, dead-letter
  queues
- `aws-sdk-go-v2` / `service/sqs` documentation
- `docs/architecture/nfr-notes.md` ("No message broker" section, updated to
  reference this ADR)
- `docs/feature-plans/event-driven-alerts_plan.md` (related but separate,
  still-open decision — referenced for context only, not superseded)
