# Target architecture: request, audit and ingestion flows

> **Status:** Accepted target architecture (2026-11-01). **Owner:** Neo Jia Wen. **Last updated:** 2026-11-01.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

## Numbered flows

The numbers match the arrows on the diagram.

| # | Flow |
|---|---|
| 1 | The browser loads the frontend through CloudFront over HTTPS, filtered by AWS WAF. CloudFront reads the static files from a private S3 bucket using Origin Access Control (OAC). |
| 2 | The user signs in with Cognito managed login: OAuth 2.0 authorization-code flow with PKCE, plus MFA ([ADR-0025](../adr/ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) decision 2). On sign-out the frontend reports the logout to user-service, which audits it, then revokes the refresh token and ends the managed login session (decision 5). |
| 3 | `/api/*` requests go from CloudFront to API Gateway, which validates the Cognito JWT and applies throttling. |
| 4 | API Gateway forwards the request through the VPC Link (private integration) to the internal ALB over HTTPS. |
| 5 | The ALB routes by path to the ECS Fargate services (auto scaling across two AZs). Every service it routes to validates the JWT again and talks only to its own database (SQL over TLS). The auditor has no ALB route. transaction-service checks the caller's token itself and asks account-service, with that token, whether the caller may see an account before reading its transactions ([ADR-0064](../adr/ADR-0064-transaction-service-authenticates-its-own-reads.md)). |
| 6 | A service writes the business change and an outbox row in one database transaction; a service that builds its audit events in request middleware saves the row in a short transaction of its own right after the commit instead ([ADR-0020](../adr/ADR-0020-audit-write-path-outbox-shared-sqs.md) exception). A relay then sends the audit event to the shared SQS standard queue (`audit-insert`). Every service with a database does this; one path is drawn. The ingest job relays its `audit_outbox` rows at the end of each run ([Transaction ingestion (Feature 3)](#transaction-ingestion-feature-3)). The Cognito triggers and dashboard-service have no database, so they send straight to the queue ([Authentication events](../security/security-model.md#authentication-events)). |
| 7 | The auditor consumes the events and appends them to the hash chain in audit-db. A message that fails 5 receives moves to the dead-letter queue and raises an alarm. |
| 8 | user-service's activity feed reads audit data from the auditor over gRPC through ECS Service Connect (TLS, user JWT in metadata). Admins reach tamper findings through user-service, which requires the ADMIN role and forwards the admin's token to the auditor's internal REST API (`/api/corrupted-logs`, HTTPS); the auditor accepts admin tokens only. Only other services call the auditor. |
| 9 | EventBridge Scheduler invokes the transaction-ingest Lambda about every 5 minutes ("9 invoke"), and starts the chain-verify task daily. The same scheduler bus ("9 · 11 · 13 scheduled starts") also starts the alert worker (11, every 4 hours) and the health analysis task (13, daily at 08:00 SGT). transaction-ingest is the same Go code as transaction-service (`services/sftp/receiver`), built as a Lambda. |
| 9a | The ingest Lambda (VPC-attached) connects out through the NAT gateway and internet gateway to the SFTP server on EC2 (SSH key authentication, pinned host key). It imports the transactions into transactions-db and audits each file with a FILE_PROCESSING_COMPLETED event. |
| 9b | chain-verify reads and verifies the chain in audit-db ("verify chain"), then writes the daily chain head to the S3 Object Lock bucket, and records any mismatch in the tamper-findings table in audit-db. |
| 10 | client-service encrypts the 11 personal client fields with a per-client data key. It fetches that key, wrapped by a symmetric KMS key, from the client-keys DynamoDB table (through the DynamoDB gateway endpoint) and unwraps it with KMS Decrypt. |
| 11 | The alert worker (x-factor 1) runs every 4 hours. It recalculates account sector exposures from holdings in alerts-db through the instrument master (a deterministic lookup; unknown instruments are UNCLASSIFIED, never guessed), and picks instruments held by at least 2 clients and at least 20% of clients with positions, or making up at least 50% of one account's value. Holdings are the portfolios agents record against CRM accounts ([ADR-0066](../adr/ADR-0066-crm-linked-current-alert-holdings.md)). |
| 11a | Only public terms (company, ticker, sector, event keywords, approved domains, dates) go to the Tavily search API, a third-party service called directly over HTTPS through the NAT gateway, with its API key from Secrets Manager ([ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md)). No client, account, holding, agent or count data leaves Singapore. |
| 11b | Singapore checks each result's domain and date again and removes duplicates. Amazon Nova 2 Lite, called from Singapore through its global inference profile, classifies the public news and writes one shared, cited summary; it receives no client data, and AWS may process the request outside Singapore. Singapore then matches holdings deterministically (ticker, sector, company), scores materiality, adds each client's private context without the model, and routes the alert to the assigned agent (`/api/alerts`). Agent decisions are audited. |
| 12 | dashboard-service (Feature 7, additional) has no database. It fans out to user, client, account and transaction aggregate endpoints with the caller's JWT over Service Connect; transaction-service scopes the transaction totals to the caller's accounts itself ([ADR-0064](../adr/ADR-0064-transaction-service-authenticates-its-own-reads.md)). It caches each caller's result for 3 minutes, and sends audit events straight to SQS (best-effort). |
| 13 | The health analysis task (x-factor 3) runs daily at 08:00 SGT. It reads matched activity from transaction-service and agent assignments from client-service, through the internal ALB with a Cognito client-credentials token (a scheduled task cannot use Service Connect). Its Java rules engine computes signals, severity and evidence, and upserts follow-up tasks in health-db with an outbox audit event, which the task API relays to the audit queue. A failed run exits non-zero and raises an SNS alert. |
| 13a | Only banded facts (signal type, % changes, counts, windows) go to Bedrock for wording: Amazon Nova Lite, called from Singapore through its Asia-Pacific profile, so AWS may process them in another Asia-Pacific Region ([ADR-0067](../adr/ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md)). If Bedrock fails, a deterministic template is used, so task creation never depends on AI. |
| 14 | Agent engagement (x-factor 2, Feature 5): the ALB routes `/api/engagement/*` to engagement-service, which validates the JWT, checks with client-service that the agent owns any linked client, and keeps events, the mail queue, drafts, holidays and preferences in its own engagement-db with an outbox for audit events. It finds the agent's e-mail address with Cognito ListUsers by `sub`. For a follow-up from a Client Health task or an alert, it reads that source with the agent's own token through the internal ALB and keeps only the source's client field ([ADR-0068](../adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md)). |
| 14a | A mail dispatcher in every engagement-service task claims due mail with `FOR UPDATE SKIP LOCKED` and a lease and sends it through Amazon SES: iCalendar invites, updates and cancellations to the agent (`METHOD:REQUEST` and `CANCEL`, stable `UID`, rising `SEQUENCE`), and up to two reminders per event. A follow-up e-mail goes through SES when the agent presses Send, not through the queue, with the agent in Bcc; the client's address is fetched from client-service at send time and is not stored. |
| 14b | Follow-up drafts: Amazon Nova Lite through Bedrock Runtime in Singapore with its Asia-Pacific profile ([ADR-0067](../adr/ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md)), given placeholders, the purpose and public holiday data only. Output must pass a safety screen or a template is used; a kill switch and caps apply. The agent edits the draft as plain text and sends it; the draft's text is cleared once it is sent or discarded, and open drafts unchanged for 14 days are discarded. |
| 14c | engagement-service adds newly published Singapore public holidays from MOM's consolidated dataset on data.gov.sg at start-up and daily, out through the NAT gateway. It only adds rows, and alarms on a missing next year or a mismatch. |
| 15 | Admin client reassignment: `POST /api/clients/reassignments` in client-service checks with user-service that the target is an enabled agent, then moves the clients' accounts through account-service's `PUT /ownership/accounts` while it holds the clients locked, so nothing moves if either fails. One audit event per client records the agent before and after ([ADR-0060](../adr/ADR-0060-client-reassignment.md)). |
| – | Unnumbered: the dashed line between each RDS primary and its standby is Multi-AZ synchronous replication with automatic failover (shown once, applies to every database). "After 5 failures" marks the redrive from the audit queue to its dead-letter queue. |

## Audit write path: SQS, not gRPC

Recorded in [ADR-0020](../adr/ADR-0020-audit-write-path-outbox-shared-sqs.md) (Accepted).

How an audit event travels by default, using a client update as the example:

| Step | Who | What happens |
|---|---|---|
| 1 | Browser → client-service | `PUT /api/clients/{id}` |
| 2 | client-service → clients-db | In one transaction: `UPDATE clients ...` and `INSERT` an outbox row (event_id, before, after, actor); then `COMMIT`. |
| 3 | client-service → browser | `200 OK`. The user gets a response without waiting for the auditor. |
| 4 | Outbox relay (inside the service) | Every 2 seconds: locks the oldest unsent outbox rows, sends each to the shared audit queue with its `event_id` and `source_service`, and deletes each row once SQS accepts it. |
| 5 | auditor → SQS | `ReceiveMessage`. |
| 6 | auditor → audit-db | Locks the chain head and inserts the `audit_log` row (sequence_no, parent_hash, hash). |
| 7 | auditor → SQS | `DeleteMessage`, only after the insert commits. A message that fails 5 times moves to the dead-letter queue and raises an alarm. |

**Exception ([ADR-0020](../adr/ADR-0020-audit-write-path-outbox-shared-sqs.md) decision 1).** A producer that already builds its audit events in request middleware, after the commit, saves the outbox row in a short transaction of its own right after the request, instead of in step 2. user-service, client-profile-management-service and account-service work this way on `dev`; the exception exists because of the cost of changing them. For these producers, a crash between the commit and the outbox write loses that event.

### Why audit writes go through SQS and not a synchronous gRPC call

Every business action in Features 1–6 must produce an audit record. How that record reaches the auditor decides whether the system can stay available, consistent and fast at the same time.

| NFR | With synchronous gRPC writes | With outbox + SQS (chosen) |
|---|---|---|
| **2. Availability** | An auditor outage or deployment blocks every business write. Each service would be down whenever the auditor is. | Business services keep working while the auditor is down. Events wait in SQS (up to 14 days' retention) and are processed when it recovers. |
| **4. Consistency** | The business commit and the audit call are two separate operations. Either can fail after the other succeeds: an action without an audit record, or an audit record for an action that rolled back. | The business row and the outbox row commit in **one database transaction**, so an audit event exists exactly when the business change exists. Producers under the exception save the row just after the commit, so for them an event is lost only if the service crashes in the few milliseconds between the two. The audit log itself is eventually consistent, typically seconds behind. |
| **5. Resiliency** | Retries sit inside the user's request and add latency. When retries run out, the event is lost. | SQS retries automatically. Failed messages go to a dead-letter queue for inspection and replay. The auditor processes each event once even if SQS delivers it twice (de-duplicated by `event_id`). |
| **1. Scalability / performance** | The hash chain must be appended in strict order, so inserts are serialised. Under load, every business request would wait behind that lock. | The queue absorbs bursts. Business requests never wait on chain insertion. The auditor auto-scales on queue backlog. |
| **8. Security (Zero Trust)** | The auditor must trust a caller-supplied "source service" value. | Only producer roles may send to the audit queue (IAM and the queue policy). The queue is shared, so it does not by itself show which service sent an event; the auditor is to check each message's SQS `SenderId` (the sending IAM role) against its `source_service` field ([open item 18](open-items.md#18-audit-sender-verification)). |
| **9. Durability** | An event exists only in memory until the call succeeds. | The event is stored first in the service's database, then in SQS, which stores data redundantly across AZs. |
| **3. Maintainability** | Every producer depends on the auditor's API version and uptime. | Producers only depend on an event format. The auditor can be redeployed, scaled or changed independently. |

**Trade-offs accepted:**

- The audit log lags the business change by a few seconds.
- For producers under the exception, a crash between the business commit and the outbox write loses that event.
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

Recorded in [ADR-0021](../adr/ADR-0021-audit-read-path-grpc-service-connect.md) (Accepted).

How the activity feed is served. It is the dashboards' "Recent Activities" in the spec's mock-up: an agent sees their own activities, an admin those of all agents.

| Step | Who | What happens |
|---|---|---|
| 1 | Browser → API Gateway + ALB | `GET /api/activities` with the user's JWT; an admin may add an agent and a time window. |
| 2 | API Gateway + ALB → user-service | Forwards the request (JWT already validated once). |
| 3 | user-service | Validates the JWT again. For an agent, the actor is always the caller's own `sub`. |
| 4 | user-service → Envoy (Service Connect) | A paged gRPC call listing changes by actor and time window, with the JWT in metadata and a 2-second deadline. |
| 5 | Envoy → auditor | Routed to a healthy auditor task in either AZ. |
| 6 | auditor | An interceptor validates the JWT and enforces the role filter: an agent gets only entries whose actor is the token's `sub`. |
| 7 | auditor → audit-db | Paged `SELECT` of changes only: no reads, dashboard views, sign-ins or system events. |
| 8 | auditor → user-service → browser | Entries with IDs and action details, the actor's name added by user-service, returned as JSON (documented in its OpenAPI spec). |

### Audit read decisions

**The auditor is internal-only.** The browser never calls it; there is no ALB route or public endpoint for it.

- This keeps the tamper-resistant store's network exposure to a minimum (NFR 8).
- One activity feed, `GET /api/activities` in user-service, serves both audiences: admins see all agents' activities, agents their own. It is Feature 1 in the base spec, so it lives in user-service, not the business dashboard. It shows changes only.
- Admins reach tamper findings through user-service too, over a small internal REST API on the auditor (`/api/corrupted-logs`, HTTPS) instead of gRPC: an admin-only list of findings needs neither paging nor a typed streaming contract. user-service requires the ADMIN role and forwards the admin's token; the auditor checks it again and accepts admin tokens only. The contract is `docs/api/corrupted-logs-api.yaml`. Which operations admins have on findings is still [open item 13](open-items.md#13-tamper-findings-and-chain-verify-on-dev).
- These are REST endpoints documented in user-service's OpenAPI spec, which covers the OpenAPI requirement for everything a client can reach.

**Reads use gRPC, not SQS.** A read needs an immediate answer to a specific question, one page at a time; that is request/response by nature. gRPC also gives a typed contract generated from one `.proto` file for the Go server and the Java and Go callers.

**Service Connect handles discovery and load balancing.**

- The auditor's ECS service exposes a named port with `appProtocol: grpc` and the client alias `auditor:50051`.
- gRPC keeps long-lived HTTP/2 connections open. Plain DNS or a layer-4 load balancer would send all of a caller's traffic to one task; Service Connect's Envoy sidecar spreads calls across healthy tasks in both AZs (NFR 1, 2).

**Zero Trust on every call (NFR 8):**

- **Network:** the auditor's security group opens the gRPC port, and the HTTPS port for tamper findings, only to the security groups of the named calling services.
- **User identity:** callers forward the end user's JWT in gRPC metadata. The auditor validates it itself (signature, issuer, expiry) and enforces role filters from the token alone: an Admin may query any actor's activity; an Agent's queries return only entries whose actor is their own `sub`, even if the caller asks for more.
- **Service identity:** background callers with no user (for example an F4 AI job) use a Cognito client-credentials token with an `audit/read` scope.
- **Caller-side checks:** calling services also enforce their own ownership rules. Both sides check.

**Encryption in transit:** the auditor serves gRPC over TLS with a certificate from a project CA generated by Terraform, avoiding AWS Private CA costs. Because the TLS is end-to-end, Envoy balances connections rather than individual calls, so the server sets `keepalive.MaxConnectionAge` (about 5 minutes) and callers reconnect regularly.

**Resiliency (NFR 5):** callers set a deadline on every call (about 2 seconds), retry only on `UNAVAILABLE`, and use a circuit breaker (Resilience4j in Java, gobreaker in Go). If the auditor is unavailable, the feed shows "activity temporarily unavailable"; business actions are unaffected because writes go through SQS.

**Contract and backward compatibility (NFR 3):** the `.proto` lives in a shared `proto/` directory under a versioned package (`audit.v1`). Fields are only added with new field numbers, never renumbered or reused. CI runs `buf lint` and `buf breaking` and fails any pull request that would break existing callers. Responses are paged (`page_size`, `page_token`).

## Transaction ingestion (Feature 3)

Recorded in [ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) (Accepted 2026-10-14).

The core banking system is **external** to the CRM. For the project it is simulated by an **SFTP server on an EC2 instance** in its own stack; the architecture treats it as a third-party system reached over the internet. When a transaction file is ready, a **scheduled Lambda function** on the CRM side opens an outbound SSH connection through the NAT gateway, reads the file over SFTP, and imports its transactions.

| Step | Who | What happens |
|---|---|---|
| 1 | File generator → SFTP server | Uploads `reports/report-<time>-<id>.json.tmp`, then renames it to `.json`. The file is now ready. |
| 2 | EventBridge Scheduler → transaction-ingest | Invokes the Lambda (for example every 5 minutes, or on demand). |
| 3 | transaction-ingest → SFTP server | SSH connect: key authentication, pinned host key, retry and circuit breaker. On connection or authentication failure it writes a `CONNECTION_FAILURE` event to `audit_outbox`, relays it (step 8), and stops. |
| 4 | transaction-ingest → SFTP server | Lists `reports/`, ignoring `.tmp` uploads. |
| 5 | For each ready file | Reads the file; verifies that it is a JSON array of transactions, and each row (no Client ID lookup; [Ingestion decisions](#ingestion-decisions)). Once less than a minute of the invocation remains, it starts no more files; the rest wait for the next run. |
| 6 | transaction-ingest → transactions-db | `INSERT ... ON CONFLICT (transaction_id) DO UPDATE` status, only while the stored status is Pending. In the same transaction, writes the file's `FILE_PROCESSING_COMPLETED` audit event to `audit_outbox`, listing each rejected row's record number and reason. |
| 7 | transaction-ingest → SFTP server | Moves the file to `processed/` or `rejected/`. |
| 8 | transaction-ingest → SQS | At the end of the run, sends every unsent `audit_outbox` row (including any left from earlier runs) to the audit queue and deletes it once SQS accepts it. Each `FILE_PROCESSING_COMPLETED` event holds start, end, status, processed/accepted/rejected counts and reasons. If SQS cannot be reached, the rows wait for the next run. |

**Audit delivery.** transaction-ingest keeps its events in the `audit_outbox` table in transactions-db. transaction-service keeps its read events in its own `read_audit_outbox` ([ADR-0064](../adr/ADR-0064-transaction-service-authenticates-its-own-reads.md)). The two are one Go codebase (`services/sftp/receiver`), deployed as an ECS service and a Lambda, each with its own database login, so neither relays the other's events and each event reaches SQS under the IAM role that produced it ([open item 18](open-items.md#18-audit-sender-verification)). The ingest job has no long-running relay loop, so it relays its own rows at the end of every run; a failed send is retried on the next run, about 5 minutes later. A failure event has no import to join, so it is saved to `audit_outbox` on its own; if that save fails too, typically because the database is down, the event is sent straight to SQS.

### How a file is marked ready

The file generator uploads each file with a `.tmp` suffix and then renames it to its final name. A rename within one directory is atomic, so the worker never sees a half-written file under its final name. There is no checksum file: SSH checks the data in transit, and a truncated or damaged file fails to parse and is rejected ([ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md)).

### How the spec's audit requirements are met

Every ingestion audit event has event type `TRANSACTION_INGESTION`; its action says what happened. An imported file gets one `FILE_PROCESSING_COMPLETED` event. Each failure gets a `FILE_FORMAT_INVALID`, `CONNECTION_FAILURE` or `SYSTEM_EXCEPTION` event holding the file name (empty for a connection failure), the stage, the error message and `occurred_at`.

A failure that repeats on every run is audited once per episode, when it first happens, not on every run. A connection failure's episode ends when a run connects and lists `reports/`; an account lookup failure's when a lookup succeeds; any other failure on a file when the file is imported or leaves `reports/`. Each episode has its own `occurred_at`, and so its own event ID, so the auditor stores a second outage instead of dropping it as a repeat of the first. Episodes are kept in memory, so a restart or a new Lambda execution environment audits an ongoing failure again ([ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 5).

| Spec audit requirement | How it is detected |
|---|---|
| File processing start/end, status (Successful / Partially Successful / Failed) | Recorded for every imported file in its `FILE_PROCESSING_COMPLETED` audit event. The status is Failed when every row is rejected. |
| Records processed, accepted and rejected | Counted per file, in the same event. |
| Records that could not be parsed, with reason | Record number and reason, listed in the file's `FILE_PROCESSING_COMPLETED` audit event, with no raw PII. |
| Corrupted, invalid or unsupported file formats | An unknown extension, or a file that is not a JSON array of transactions, which is how a truncated or damaged file shows. The whole file is rejected, moved to `rejected/` and recorded in a `FILE_FORMAT_INVALID` event. |
| External service connection failures | SSH connection, authentication or host-key failures, timeouts, and a failure to list `reports/`, after retries, each recorded in a `CONNECTION_FAILURE` event. |
| System exceptions and processing errors | Caught per file; the file stays in `reports/` for the next run and a `SYSTEM_EXCEPTION` event records the error. |

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
- **Limit:** one invocation runs for at most 15 minutes, so each run processes a bounded number of files: it starts no new file once less than a minute remains, which leaves time to relay its audit events. If files grow beyond that, the same code can move to an ECS scheduled task.
- **Runtime:** Go, as built on `dev`, deployed as a compiled binary on Lambda's OS-only runtime (`provided.al2023`). This extends ADR-0017's Go exception ([ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 7).

**No Client ID check at import (provisional; [open item 9](open-items.md#9-client-id-validation-at-import)):**

- Ingest validates the file and each row on its own: format, required fields, type (Deposit/Withdrawal), amount, date and status. It does **not** check each row's Client ID against client-service.
- The spec does not require that check. It would also make imports depend on client-service being available, and a Lambda function cannot use ECS Service Connect (its Envoy sidecar runs only inside ECS tasks), so the call would need another route.
- Client ownership must instead be enforced when transactions are **read**: an agent may only see transactions of clients they own (spec: agents "have access only to their own clients"). transaction-service enforces it: before reading an account's transactions, it checks with account-service, using the caller's token, that the caller is an admin or the account's owner ([ADR-0064](../adr/ADR-0064-transaction-service-authenticates-its-own-reads.md), [ADR-0035](../adr/ADR-0035-account-ownership-across-services.md)).
- Trade-off: a row whose Client ID matches no CRM client is stored rather than rejected. No agent owns that client, so no agent can see it. An optional reconciliation job can list such rows for an admin.
- **Account IDs are checked at import** ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)): the worker asks account-service once per file which account references exist, and marks rows `Matched` or `Unmatched`. A row on an existing account must be in the account's currency, or it is rejected ([ADR-0061](../adr/ADR-0061-imported-transactions-in-their-accounts-currency.md)). It authenticates with a Cognito client-credentials token. If account-service cannot answer, the file is left for the next run. So imports do depend on account-service. A Lambda worker cannot use Service Connect, so it calls `/internal/accounts/lookup` through the internal ALB, as the standalone scheduled tasks do ([ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 3, [ADR-0019](../adr/ADR-0019-edge-entry-chain.md) decision 4).

**Security (NFR 8):** key-based authentication only; the worker's private key and Cognito client secret in Secrets Manager, read when it starts rather than kept in Lambda environment variables; TLS to transactions-db; the server's host-key fingerprint pinned through the Lambda's `SFTP_HOST_KEY_FINGERPRINT` environment variable, which Terraform sets from the sftp-server stack's output (the worker refuses to connect on a mismatch, which protects against man-in-the-middle attacks); a chrooted, SFTP-only account with no shell; the server accepts port 22 only from the CRM NAT gateway's Elastic IP, and only the ingestion worker's security group may open outbound port 22.

**Idempotency (NFR 4, 9):** a file leaves `reports/` only after its import commits, so a finished file is not read again. If moving it fails, the next run reads it again: transactions are inserted with `ON CONFLICT (transaction_id)`, so a replayed or overlapping file never creates duplicates, and a later file can move a transaction from Pending to Completed or Failed. Only a Pending status changes: a replayed older file cannot reopen a completed or failed transaction, and every other field keeps its first imported value. A file read twice gets two `FILE_PROCESSING_COMPLETED` events, which records what happened.
