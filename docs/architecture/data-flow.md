# Target architecture: request, audit and ingestion flows

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-01.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](system-overview.md); each decision is recorded as a Proposed ADR in the [ADR index](../adr/README.md).

## Numbered flows

The numbers match the arrows on the diagram.

| # | Flow |
|---|---|
| 1 | The browser loads the frontend through CloudFront over HTTPS, filtered by AWS WAF. CloudFront reads the static files from a private S3 bucket using Origin Access Control (OAC). |
| 2 | The user signs in with Cognito managed login: OAuth 2.0 authorization-code flow, plus MFA. PKCE is proposed but not yet confirmed ([open item 20](open-items.md#20-sign-in-flow-and-pkce-unconfirmed)). |
| 3 | `/api/*` requests go from CloudFront to API Gateway, which validates the Cognito JWT and applies throttling. |
| 4 | API Gateway forwards the request through the VPC Link (private integration) to the internal ALB over HTTPS. |
| 5 | The ALB routes by path to the ECS Fargate services (auto scaling across two AZs). Every service validates the JWT again and talks only to its own database (SQL over TLS). The auditor has no ALB route. |
| 6 | A service writes the business change and an outbox row in one database transaction. A relay then sends the audit event to the shared SQS standard queue (`audit-insert`). Every service with a database does this; one path is drawn. The ingest Lambda relays its own `ingest_outbox` rows at the end of each run ([Transaction ingestion (Feature 3)](#transaction-ingestion-feature-3)). The Cognito triggers have no database, so they send straight to the queue ([Authentication events](../security/security-model.md#authentication-events)). |
| 7 | The auditor consumes the events and appends them to the hash chain in audit-db. A message that fails 5 receives moves to the dead-letter queue and raises an alarm. |
| 8 | Activity views, and the admin view of tamper findings, read audit data from the auditor over gRPC through ECS Service Connect (TLS, user JWT in metadata). Only other services call the auditor. |
| 9 | EventBridge Scheduler invokes the transaction-ingest Lambda about every 5 minutes ("9 invoke"), and starts the chain-verify task daily. The same scheduler bus ("9 · 11 · 13 scheduled starts") also starts the alert worker (11, every 6 hours) and the health analysis task (13, daily at 08:00 SGT). |
| 9a | The ingest Lambda (VPC-attached) connects out through the NAT gateway and internet gateway to the SFTP server on EC2 (SSH key authentication, pinned host key). It imports the transactions into transactions-db and audits a FILE_PROCESSED event. |
| 9b | chain-verify reads and verifies the chain in audit-db ("verify chain"), then writes the daily chain head to the S3 Object Lock bucket, and records any mismatch in the tamper-findings table in audit-db. |
| 10 | client-service encrypts every client field except IDs and ownership with a per-client data key. It fetches that key, wrapped by a symmetric KMS key, from the client-keys DynamoDB table (through the DynamoDB gateway endpoint) and unwraps it with KMS Decrypt. |
| 11 | The alert worker (x-factor 1) runs every 6 hours. It recalculates account sector exposures from holdings in alerts-db through the instrument master (a deterministic lookup; unknown instruments are UNCLASSIFIED, never guessed), and picks instruments held by at least 2 clients and at least 5% of clients. |
| 11a | Only public terms (company, ticker, sector, event keywords, approved domains, dates) go to Bedrock AgentCore web search in Tokyo, out through the NAT gateway. No client, account, holding, agent or count data leaves Singapore. |
| 11b | Singapore validates and de-duplicates the public event report, matches holdings deterministically (ticker, sector, company), scores materiality, writes the summary and private client context with Bedrock in Singapore, and routes the alert to the assigned agent (`/api/alerts`). Agent decisions are audited. |
| 12 | dashboard-service (x-factor 2) has no database. It fans out to user, client, account and transaction aggregate endpoints with the caller's JWT over Service Connect (four arrows on the diagram), caches each caller's result for 3 minutes, and sends audit events straight to SQS (best-effort). |
| 13 | The health analysis task (x-factor 3) runs daily at 08:00 SGT. It reads matched activity from transaction-service and agent assignments from client-service, through the internal ALB with a service token (a scheduled task cannot use Service Connect). Its Java rules engine computes signals, severity and evidence, and upserts follow-up tasks in health-db with an outbox audit event. |
| 13a | Only banded facts (signal type, % changes, counts, windows) go to Bedrock in Singapore for wording. If Bedrock fails, a deterministic template is used, so task creation never depends on AI. |
| – | Unnumbered: the dashed line between each RDS primary and its standby is Multi-AZ synchronous replication with automatic failover (shown once, applies to every database). "After 5 failures" marks the redrive from the audit queue to its dead-letter queue. |

## Audit write path: SQS, not gRPC

Recorded in [ADR-0020](../adr/ADR-0020-audit-write-path-outbox-shared-sqs.md) (Proposed).

How an audit event travels, using a client update as the example:

| Step | Who | What happens |
|---|---|---|
| 1 | Browser → client-service | `PUT /api/clients/{id}` |
| 2 | client-service → clients-db | In one transaction: `UPDATE clients ...` and `INSERT` an outbox row (event_id, before, after, actor); then `COMMIT`. |
| 3 | client-service → browser | `200 OK`. The user gets a response without waiting for the auditor. |
| 4 | Outbox relay (inside the service) | Every few seconds: reads unsent outbox rows, sends each to the shared audit queue with its `event_id` and `source_service`, and marks the rows sent. |
| 5 | auditor → SQS | `ReceiveMessage`. |
| 6 | auditor → audit-db | Locks the chain head and inserts the `audit_log` row (sequence_no, parent_hash, hash). |
| 7 | auditor → SQS | `DeleteMessage`, only after the insert commits. A message that fails 5 times moves to the dead-letter queue and raises an alarm. |

### Why audit writes go through SQS and not a synchronous gRPC call

Every business action in Features 1–6 must produce an audit record. How that record reaches the auditor decides whether the system can stay available, consistent and fast at the same time.

| NFR | With synchronous gRPC writes | With outbox + SQS (chosen) |
|---|---|---|
| **2. Availability** | An auditor outage or deployment blocks every business write. Each service would be down whenever the auditor is. | Business services keep working while the auditor is down. Events wait in SQS (up to 14 days' retention) and are processed when it recovers. |
| **4. Consistency** | The business commit and the audit call are two separate operations. Either can fail after the other succeeds: an action without an audit record, or an audit record for an action that rolled back. | The business row and the outbox row commit in **one database transaction**, so an audit event exists exactly when the business change exists. The audit log itself is eventually consistent, typically seconds behind. |
| **5. Resiliency** | Retries sit inside the user's request and add latency. When retries run out, the event is lost. | SQS retries automatically. Failed messages go to a dead-letter queue for inspection and replay. The auditor processes each event once even if SQS delivers it twice (de-duplicated by `event_id`). |
| **1. Scalability / performance** | The hash chain must be appended in strict order, so inserts are serialised. Under load, every business request would wait behind that lock. | The queue absorbs bursts. Business requests never wait on chain insertion. The auditor auto-scales on queue backlog. |
| **8. Security (Zero Trust)** | The auditor must trust a caller-supplied "source service" value. | Only producer roles may send to the audit queue (IAM and the queue policy). The queue is shared, so it does not by itself show which service sent an event; the auditor is to check each message's SQS `SenderId` (the sending IAM role) against its `source_service` field ([open item 18](open-items.md#18-audit-sender-verification)). |
| **9. Durability** | An event exists only in memory until the call succeeds. | The event is stored first in the service's database, then in SQS, which stores data redundantly across AZs. |
| **3. Maintainability** | Every producer depends on the auditor's API version and uptime. | Producers only depend on an event format. The auditor can be redeployed, scaled or changed independently. |

**Trade-offs accepted:**

- The audit log lags the business change by a few seconds.
- Each service needs an outbox table and a relay loop.
- SQS delivers "at least once", so the auditor must be idempotent.

### Why one shared queue for all producers

- **Simple to run.** One queue, one dead-letter queue, one consumer loop in the auditor, one set of alarms and one redrive procedure, however many services produce events. This is what the auditor on `dev` consumes today (`audit-insert`, with `audit-insert-dlq`).
- **Order does not matter.** The auditor fixes the chain order when it inserts each event, so separate streams per producer gain nothing.
- **Enough capacity.** Audit volume is small. The queue absorbs bursts, and the auditor scales on queue backlog.
- **Cost:** SQS charges per request, not per queue. The auditor long-polls one queue (20-second waits); at this volume that stays under about USD 1/month.

**Trade-offs accepted:**

- **The queue does not prove the sender.** Each message names its `source_service`. IAM limits sending to producer roles, and the auditor is to compare the message's `SenderId` with that name ([open item 18](open-items.md#18-audit-sender-verification)). Until then, a compromised producer could claim to be another service.
- **No burst isolation.** A large transaction import can delay other services' audit events by seconds to minutes. Audit writes are asynchronous anyway, and a CloudWatch alarm on the age of the oldest message flags a backlog.
- **One dead-letter queue for everyone.** Failed events from all producers land together; each carries its `event_id` and `source_service` for triage and replay.

**Not chosen: one queue per producer.** Separate queues would isolate bursts and failures, and queue permissions would prove the sender. They would also need a queue, a dead-letter queue, alarms and a polling loop for each of more than eight producers (the services, the ingest Lambda and the Cognito triggers). At this volume the team chose the simpler layout.

### Why an SQS standard queue (not Kafka, not FIFO)

The auditor was first written against Kafka. SQS was chosen instead because:

- **Cost:** managed Kafka (Amazon MSK) costs roughly USD 80–95/month even at the smallest size, and a cluster cannot be paused. SQS costs almost nothing at this volume.
- **Operations:** SQS is fully managed, spans multiple AZs by default and has built-in dead-letter queues. There are no brokers, topics or partitions to run.
- **Fit:** there is one consumer, the auditor. Kafka's strengths (many consumer groups, replay from any offset, stream processing) are not needed.

**Standard queues, not FIFO.** The order in which audit events arrive does not matter: the auditor fixes the chain order when it inserts each event, and every event carries its own business timestamp. Standard queues are cheaper (about USD 0.40 versus USD 0.50 per million requests) and have no throughput limit.

Standard queues deliver **at least once** and occasionally out of order, so the auditor keeps each `event_id` under a unique constraint and skips any event it has already chained. A duplicate delivery therefore never creates a second audit record.

## Audit read path: internal gRPC

Recorded in [ADR-0021](../adr/ADR-0021-audit-read-path-grpc-service-connect.md) (Proposed).

How an agent's client activity view is served:

| Step | Who | What happens |
|---|---|---|
| 1 | Browser → API Gateway + ALB | `GET /api/clients/{id}/activity` with the user's JWT. |
| 2 | API Gateway + ALB → client-service | Forwards the request (JWT already validated once). |
| 3 | client-service | Validates the JWT again and checks the agent owns the client. |
| 4 | client-service → Envoy (Service Connect) | gRPC `GetAuditLog(client_id, page)` with the JWT in metadata and a 2-second deadline. |
| 5 | Envoy → auditor | Routed to a healthy auditor task in either AZ. |
| 6 | auditor | An interceptor validates the JWT and enforces the role filter. |
| 7 | auditor → audit-db | Paged `SELECT`. |
| 8 | auditor → client-service → browser | Audit entries with PII masked, returned as JSON (documented in the service's OpenAPI spec). |

### Audit read decisions

**The auditor is internal-only.** The browser never calls it; there is no ALB route or public endpoint for it.

- This keeps the tamper-resistant store's network exposure to a minimum (NFR 8).
- Services that own a business context present their own activity views: client history in client-service (`GET /api/clients/{id}/activity`), the admin-wide view of all agents' activity in user-service (`GET /api/activities`, Admin only), and the admin view of tamper findings, also in user-service, which calls a new auditor gRPC method (for example `ListTamperFindings`).
- Those views are REST endpoints documented in each service's OpenAPI spec, which covers the OpenAPI requirement for everything a client can reach.

**Reads use gRPC, not SQS.** A read needs an immediate answer to a specific question, one page at a time; that is request/response by nature. gRPC also gives a typed contract generated from one `.proto` file for the Go server and the Java and Go callers.

**Service Connect handles discovery and load balancing.**

- The auditor's ECS service exposes a named port with `appProtocol: grpc` and the client alias `auditor:50051`.
- gRPC keeps long-lived HTTP/2 connections open. Plain DNS or a layer-4 load balancer would send all of a caller's traffic to one task; Service Connect's Envoy sidecar spreads calls across healthy tasks in both AZs (NFR 1, 2).

**Zero Trust on every call (NFR 8):**

- **Network:** the auditor's security group opens the gRPC port only to the security groups of the named calling services.
- **User identity:** callers forward the end user's JWT in gRPC metadata. The auditor validates it itself (signature, issuer, expiry) and enforces role filters: an Admin may query any activity; an Agent's queries are restricted to their own clients and actions, even if the caller asks for more.
- **Service identity:** background callers with no user (for example an F4 AI job) use a Cognito client-credentials token with an `audit/read` scope.
- **Caller-side checks:** calling services also enforce their own ownership rules. Both sides check.

**Encryption in transit:** the auditor serves gRPC over TLS with a certificate from a project CA generated by Terraform, avoiding AWS Private CA costs. Because the TLS is end-to-end, Envoy balances connections rather than individual calls, so the server sets `keepalive.MaxConnectionAge` (about 5 minutes) and callers reconnect regularly.

**Resiliency (NFR 5):** callers set a deadline on every call (about 2 seconds), retry only on `UNAVAILABLE`, and use a circuit breaker (Resilience4j in Java, gobreaker in Go). If the auditor is unavailable, activity views show "history temporarily unavailable"; business actions are unaffected because writes go through SQS.

**Contract and backward compatibility (NFR 3):** the `.proto` lives in a shared `proto/` directory under a versioned package (`audit.v1`). Fields are only added with new field numbers, never renumbered or reused. CI runs `buf lint` and `buf breaking` and fails any pull request that would break existing callers. Responses are paged (`page_size`, `page_token`).

## Transaction ingestion (Feature 3)

Recorded in [ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) (Proposed).

The core banking system is **external** to the CRM. For the project it is simulated by an **SFTP server on an EC2 instance** in its own stack; the architecture treats it as a third-party system reached over the internet. When a transaction file is ready, a **scheduled Lambda function** on the CRM side opens an outbound SSH connection through the NAT gateway, reads the file over SFTP, and imports its transactions.

| Step | Who | What happens |
|---|---|---|
| 1 | File generator → SFTP server | Uploads `inbox/txn_20261023_01.csv.tmp`, writes a `.sha256` checksum, then renames the upload to `.csv`. The file is now ready. |
| 2 | EventBridge Scheduler → transaction-ingest | Invokes the Lambda (for example every 5 minutes, or on demand). |
| 3 | transaction-ingest → SFTP server | SSH connect: key authentication, pinned host key, retry and circuit breaker. On connection or authentication failure it writes a `FILE_PROCESSED` event with status Failed to `ingest_outbox`, relays it (step 8), and stops. |
| 4 | transaction-ingest → SFTP server | Lists `inbox/`, ignoring `.tmp` files. |
| 5 | For each ready file | Looks up `ingestion_file` (name, sha256) and skips it if already processed; reads the file and checksum; verifies the checksum, format and each row (no Client ID lookup; [Ingestion decisions](#ingestion-decisions)). |
| 6 | transaction-ingest → transactions-db | `INSERT ... ON CONFLICT (transaction_id) DO UPDATE` status; records rejected rows with reasons. In the same transaction, records the file in `ingestion_file` and writes its `FILE_PROCESSED` audit event to `ingest_outbox`. |
| 7 | transaction-ingest → SFTP server | Moves the file to `processed/` or `rejected/`. |
| 8 | transaction-ingest → SQS | At the end of the run, sends every unsent `ingest_outbox` row (including any left from earlier runs) to the audit queue and marks it sent. Each `FILE_PROCESSED` event holds start, end, status, processed/accepted/rejected counts and reasons. If SQS cannot be reached, the rows wait for the next run. |

**Audit delivery.** The Lambda has its own `ingest_outbox` table in transactions-db, separate from transaction-service's `outbox` table. Each component's database user can use only its own table, so neither relays the other's events, and each event reaches SQS under the IAM role that produced it ([open item 18](open-items.md#18-audit-sender-verification)). A Lambda has no long-running relay loop, so it relays its own rows at the end of every run; a failed send is retried on the next run, about 5 minutes later.

### How a file is marked ready

The file generator uploads each file with a `.tmp` suffix, writes a `.sha256` checksum file, and then renames the upload to its final name. A rename within one directory is atomic, so the worker never sees a half-written file under its final name.

### How the spec's audit requirements are met

| Spec audit requirement | How it is detected |
|---|---|
| File processing start/end, status (Successful / Partially Successful / Failed) | Recorded for every file in the `FILE_PROCESSED` audit event and the `ingestion_file` table. |
| Records processed, accepted and rejected | Counted per file. |
| Records that could not be parsed, with reason | Row number and reason stored in transactions-db; reasons summarised in the audit event, with no raw PII. |
| Corrupted, invalid or unsupported file formats | Checksum mismatch, unknown extension, wrong header or wrong encoding. The whole file is rejected and moved to `rejected/`. |
| External service connection failures | SSH connection, authentication or host-key failures, and timeouts, after retries. |
| System exceptions and processing errors | Caught per file; the file stays in `inbox/` for the next run and the error is audited. |

### Ingestion decisions

**Why SFTP:** secure file transfer is the common way banks exchange batch files with core systems. It matches the spec's file-processing audit wording, and SSH encrypts the data in transit (NFR 8).

**Why self-hosted OpenSSH on EC2, not AWS Transfer Family:**

- Transfer Family charges roughly USD 0.30 per hour per SFTP endpoint, about USD 216/month even when idle.
- A `t4g.micro` EC2 instance with an encrypted EBS volume costs roughly USD 6–8/month and can be stopped when not in use.
- The team patches the host, and it is a single instance. That is acceptable because it represents an **external** system: the CRM tolerates its outages by retrying, auditing the failure and picking the file up on a later run.

**Why it is treated as an external system:** it represents an organisation the CRM does not own, with its own network, credentials and lifecycle. The CRM connects to it the way it would to a real bank partner: outbound over the internet from a fixed IP address (the NAT gateway's Elastic IP), authenticated with an SSH key. It is deployed from its own Terraform stack, so it can be started, stopped or broken independently to demonstrate connection failures.

**Why the reader is a scheduled Lambda function:**

- It is separate from transaction-service, which only serves the query API, so batch import problems never affect API traffic.
- It is short, periodic batch work. Lambda runs only when invoked, costs almost nothing between runs, starts faster than an ECS task, and needs no container capacity.
- It is **attached to the VPC** (network interfaces in the private app subnets of both AZs), so it can reach transactions-db and reach the SFTP server outbound through the NAT gateway.
- **Reserved concurrency is 1**, so two runs never process the same files at once. The idempotent import still protects against a file being seen twice.
- **Limit:** one invocation runs for at most 15 minutes, so each run processes a bounded number of files. If files grow beyond that, the same code can move to an ECS scheduled task.
- **Runtime:** Java on a Lambda managed runtime, or a container image if the Java 25 baseline is not yet a managed runtime. Recorded in the ingestion ADR.

**No Client ID check at import (provisional; [open item 9](open-items.md#9-client-id-validation-at-import)):**

- Ingest validates the file and each row on its own: checksum, format, required fields, type (Deposit/Withdrawal), amount, date and status. It does **not** check each row's Client ID against client-service.
- The spec does not require that check. It would also make imports depend on client-service being available, and a Lambda function cannot use ECS Service Connect (its Envoy sidecar runs only inside ECS tasks), so the call would need another route.
- Client ownership must instead be enforced when transactions are **read**: an agent may only see transactions of clients they own (spec: agents "have access only to their own clients"). How that is done is an open decision ([open item 10](open-items.md#10-how-transaction-reads-enforce-agent-ownership)).
- Trade-off: a row whose Client ID matches no CRM client is stored rather than rejected. No agent owns that client, so no agent can see it. An optional reconciliation job can list such rows for an admin.
- **Account IDs are checked at import** ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)): the worker asks account-service once per file which account references exist, and marks rows `Matched` or `Unmatched`. It authenticates with a Cognito client-credentials token. If account-service cannot answer, the file is left for the next run. So imports do depend on account-service, and a Lambda worker needs a route to it other than Service Connect.

**Security (NFR 8):** key-based authentication only; the worker's private key in Secrets Manager; the server's host key pinned in SSM Parameter Store (the worker refuses to connect on a mismatch, which protects against man-in-the-middle attacks); a chrooted, SFTP-only account with no shell; the server accepts port 22 only from the CRM NAT gateway's Elastic IP, and only the ingestion worker's security group may open outbound port 22.

**Idempotency (NFR 4, 9):** each file is recorded by name and SHA-256 in `ingestion_file`, so it is never processed twice. Transactions are inserted with `ON CONFLICT (transaction_id)`, so a replayed or overlapping file never creates duplicates, and a later file can move a transaction from Pending to Completed or Failed.
