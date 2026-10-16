# ADR-0024: Transaction ingestion: external SFTP server, scheduled VPC-attached Lambda, ready-file protocol, idempotent import

- Status: Accepted
- Decision date/time: 2026-10-14 10:10 +08:00 (Asia/Singapore); first proposed 2026-10-01 21:50 +08:00
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture` (proposed); `task/governance/adr-0024-accept` (amended, accepted); implementation on `task/transaction-integration/*` branches
- Related issue/PR: TBD
- Supersedes: the runtime [ADR-0017](ADR-0017-microservices-per-business-area.md) gave transaction-service and transaction-ingest (Java 25), which becomes Go (decision 7)
- Superseded by: N/A

## Context

Feature 3 imports transactions from the bank's core banking system, which is external to the CRM, and requires the team to host an external server for it. The spec lists audit requirements per file:
- start and end time;
- status (Successful, Partially Successful or Failed);
- records processed, accepted and rejected;
- unparseable records with a reason;
- corrupted or unsupported files;
- connection failures and system exceptions.

`project_plan.md` §13 requires the ingestion design to be decided explicitly.

The current `dev` implementation, `services/sftp/receiver`, is written in Go. This ADR adopts its file format, folders and outbox: JSON arrays of transactions named `report-<time>-<id>.json` in `reports/`, archived to `processed/` or `rejected/`, with audit events in `audit_outbox` ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)). It still differs from this design:
- a long-running poller (or a single run with `-once`), not a Lambda function;
- a file that is not valid JSON is audited but left in `reports/`, so it is read again on every run, instead of being moved to `rejected/`;
- files with another extension are ignored, not rejected and audited;
- no retries or circuit breaker around the SFTP connection within a run.

## Decision Drivers

- Security of data in transit (NFR 8)
- Cost: an idle file-transfer endpoint must not consume the budget
- Resiliency to the external system's outages (NFR 5)
- Idempotency: no duplicates on retries (NFR 4, NFR 9)
- Isolation of batch work from API traffic

## Options Considered

### Option A: AWS Transfer Family SFTP endpoint

- Pros: managed SFTP.
- Cons: about USD 0.30 per hour per endpoint, roughly USD 216/month even when idle.

### Option B: self-hosted OpenSSH on EC2, read by a scheduled VPC-attached Lambda (chosen)

- Pros: the EC2 instance costs about USD 6–8/month (`t4g.micro`, stoppable); Lambda costs almost nothing between runs and starts faster than an ECS task; import problems never affect the query API.
- Cons: the team patches the SFTP host; it is a single instance; one Lambda run is capped at 15 minutes.

### Option C: scheduled ECS task as the reader

- Pros: no 15-minute limit.
- Cons: slower start and more cost per run; the fallback if file volumes outgrow Lambda.

### Option D: import inside transaction-service

- Pros: one fewer component.
- Cons: batch problems would affect API traffic.

## Decision

1. **External SFTP server:** OpenSSH on a `t4g.micro` EC2 instance in its own Terraform stack, treated as a third-party system.
   - Chrooted, SFTP-only account with key authentication.
   - Accepts port 22 only from the CRM NAT gateway's Elastic IP.
   - A file generator writes synthetic files and can simulate faults.
2. **Ready-file protocol:** the generator uploads `report-<time>-<id>.json.tmp`, then renames it to `report-<time>-<id>.json`. The rename is atomic, so the reader never sees a half-written file. Each file is a JSON array of transactions. There is no checksum file (see Rationale).
3. **Reader:** `transaction-ingest`, a Lambda function in the private app subnets of both AZs.
   - EventBridge Scheduler invokes it about every 5 minutes.
   - Reserved concurrency is 1.
   - It connects out through the NAT gateway with key authentication and a pinned host key (in SSM Parameter Store).
   - Timeouts, retries and a circuit breaker protect the connection:
     - a run retries a failed connection a few times with backoff, but never a host-key mismatch or an authentication failure, which retrying cannot fix;
     - after several runs in a row fail to connect, the circuit breaker opens: later runs skip the connection until a cool-down has passed, and then one run tries again;
     - the breaker's state is kept in the reader's memory, like failure episodes (decision 5), so a new execution environment starts with it closed.
   - It stops starting new files when less than a minute of the invocation remains; the rest wait for the next run.
   - It calls account-service's `/internal/accounts/lookup` ([ADR-0037](ADR-0037-transactions-database-and-account-linking.md)) through the internal ALB with its client-credentials token, as standalone scheduled tasks call services ([ADR-0018](ADR-0018-ecs-fargate-compute.md)): a Lambda function cannot use ECS Service Connect.
4. **Per file:**
   - skip a file whose name ends in `.tmp`, which is still being uploaded; reject a file with any extension other than `.json` as unsupported, with a `FILE_FORMAT_INVALID` event;
   - verify that the file is a JSON array of transactions, and each row; a truncated or damaged file fails this check and is rejected with a `FILE_FORMAT_INVALID` event;
   - `INSERT ... ON CONFLICT (transaction_id) DO UPDATE` the status, only while the stored status is Pending: a later file can complete or fail a transaction but never reopen it. Every other field, the account link included, keeps its first imported value ([ADR-0037](ADR-0037-transactions-database-and-account-linking.md) decision 6);
   - in the same transaction, write the file's `FILE_PROCESSING_COMPLETED` event to `audit_outbox` ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)), with its start and end times, status, record counts, and each rejected row's record number and reason; rejected rows are not kept in a table;
   - move the file to `processed/` or `rejected/`. A file rejected as unsupported or as not a JSON array is moved only after its `FILE_FORMAT_INVALID` event is written, so no file leaves `reports/` unaudited.

   There is no table of processed files. A file leaves `reports/` only after its import commits, or after its rejection is audited; if moving it fails, the next run reads it again, and the upsert stores no duplicate transactions.
5. **On failure:**
   - on a connection or authentication failure, write a `CONNECTION_FAILURE` event and stop;
   - files stay in `reports/` for the next run;
   - on a system exception, keep the file and write a `SYSTEM_EXCEPTION` event.

   Every ingestion event has event type `TRANSACTION_INGESTION`. A failure event holds the file name (empty for a connection failure), the stage, the error message and `occurred_at`. It has no import to join, so it is saved to `audit_outbox` on its own, and sent straight to SQS if that save fails.

   **Each failure is audited once per episode**, when it first happens; `occurred_at` is that time. Repeats on later runs are not audited until the episode ends:
   - a connection failure ends when a run connects and lists `reports/`;
   - an account lookup failure ends when a lookup succeeds ([ADR-0037](ADR-0037-transactions-database-and-account-linking.md) decision 5);
   - any other failure on a file ends when the file is imported or leaves `reports/`.

   A new episode gets a new `occurred_at`, so its event ID differs from earlier ones and the auditor stores it. A retried write within one episode keeps its event ID, so it is stored once. The reader keeps episodes in memory: a restart, or a new Lambda execution environment, audits an ongoing failure again, the trade-off ADR-0037 decision 5 already accepts.
6. **No Client ID check at import (provisional):** rows are validated on their own. Ownership is enforced when transactions are read ([open item 9](../architecture/open-items.md#9-client-id-validation-at-import), [open item 10](../architecture/open-items.md#10-how-transaction-reads-enforce-agent-ownership)).
7. **Runtime: Go**, as built on `dev`. The receiver module holds both the import and transaction-service's read API, so both Feature 3 components stay in Go. This extends the Go exception in [ADR-0017](ADR-0017-microservices-per-business-area.md), whose table lists them as Java 25; once accepted, it supersedes that runtime entry only. On Lambda, the import runs as a compiled binary on the OS-only runtime (`provided.al2023`) and does what one `-once` run does today. Joseph Yau, the Feature 3 owner, confirms this on the pull request that accepts this decision, before it merges.

## Rationale

- SFTP is how banks commonly exchange batch files, and SSH encrypts the data in transit.
- Self-hosting saves about USD 208–210/month against Transfer Family. The single-instance risk is acceptable because the server stands in for an external system, which the CRM must tolerate failing anyway.
- Lambda fits short, periodic batch work, and reserved concurrency of 1 prevents overlapping runs.
- No checksum file: the rename already marks a file ready, SSH checks the data in transit, and a truncated or damaged file fails to parse and is rejected. A `.sha256` file from the same generator would add a second file, an ordering rule and new failure cases, and would still miss the generator's own mistakes. If damage that still parses must be caught, a control record in the file (record count and amount total) is the place for it ([open item 5](../architecture/open-items.md#5-sftp-file-format-and-schedule)).
- Go: the import is already built and tested in Go. Rewriting it in Java would cost delivery time for no functional gain, the reason ADR-0017 gives for its Go exception.

## Consequences

### Positive

- Cheap and isolated from API traffic.
- Outages of the external server are retried and audited, and no file is lost.
- Re-imports never create duplicates.

### Negative / Trade-offs

- The team patches the SFTP host.
- The 15-minute Lambda limit bounds each run; the same code can move to an ECS scheduled task if needed.
- A Lambda cannot use ECS Service Connect, so the account lookup goes through the internal ALB, and imports depend on it as well as on account-service. A Client ID lookup at import would need the same route.
- A later file cannot correct a transaction's amount, date or account: only a Pending status moves forward. A correction must arrive as a new transaction.
- A row whose Client ID matches no CRM client is stored rather than rejected. No agent can see it, and an optional reconciliation job can list such rows.
- Damage that still parses, such as one changed digit in an amount, is not detected unless the file format adds a control record ([open item 5](../architecture/open-items.md#5-sftp-file-format-and-schedule)).
- The `dev` receiver still needs a Lambda handler, the rejection of invalid and unsupported files, and retries with a circuit breaker on the connection (see Context). The field layout and the file schedule are still [open item 5](../architecture/open-items.md#5-sftp-file-format-and-schedule).

## Security & Privacy Impact

- Key-based authentication only. The worker's private key and its Cognito client secret are in Secrets Manager and read when the function starts, never stored as Lambda environment variables.
- TLS to transactions-db.
- A pinned host key, which protects against man-in-the-middle attacks.
- A chrooted SFTP-only account.
- An IP allow-list on the server, and outbound port 22 only from the ingest security group.
- Synthetic data only.

## Cost Impact

EC2 is about USD 6–8/month (stopped when idle). Lambda costs pennies a month. Avoiding Transfer Family saves about USD 208–210/month.

## Operational Impact

- Alarms on ingestion failure and on `Failed` file statuses.
- Ingestion counts as custom metrics.
- The SFTP server stack can be stopped or broken on purpose to demonstrate failure handling.

## Validation

- Tests for:
  - a corrupted file (truncated or damaged, so it fails to parse);
  - an unsupported format;
  - partially valid rows;
  - a replayed file (no duplicates);
  - a re-sent transaction (only a Pending status changes);
  - a server outage (one `CONNECTION_FAILURE` event per outage, and the file picked up later);
  - connection retries and the circuit breaker (no retry on a host-key mismatch; an open breaker skips the connection);
  - a host-key mismatch (refused).
- Check that every case produces the spec's audit fields.

## References

- [Transaction ingestion](../architecture/data-flow.md#transaction-ingestion-feature-3), [how a file is marked ready](../architecture/data-flow.md#how-a-file-is-marked-ready), [ingestion decisions](../architecture/data-flow.md#ingestion-decisions)
- [Open items 5](../architecture/open-items.md#5-sftp-file-format-and-schedule), [9](../architecture/open-items.md#9-client-id-validation-at-import), [10](../architecture/open-items.md#10-how-transaction-reads-enforce-agent-ownership)
- `services/sftp/receiver` (current `dev` implementation)
- AWS documentation: AWS Transfer Family pricing; Lambda VPC networking

## Revision history

- 2026-10-01 21:50 +08:00: proposed.
- 2026-10-12: before acceptance, aligned with the `dev` receiver, as decided by the decision owner: JSON files named `report-<time>-<id>.json` in `reports/`, rejected rows listed in the `FILE_PROCESSED` event instead of a table, no `ingestion_file` table, Go as the runtime (extending ADR-0017's exception), and no `.sha256` checksum file. The Lambda reader is unchanged.
- 2026-10-13: before acceptance, the audit event names in decisions 4 and 5 aligned with the `dev` receiver and the auditor: an imported file's event is `FILE_PROCESSING_COMPLETED`, and failures are `CONNECTION_FAILURE`, `FILE_FORMAT_INVALID` and `SYSTEM_EXCEPTION` events, not a `FILE_PROCESSED` event with status Failed. The decisions are otherwise unchanged.
- 2026-10-13: before acceptance, as decided by the decision owner:
  - decision 3: the reader stops starting files a minute before its timeout, and reaches account-service through the internal ALB;
  - decision 4: the upsert moves only a Pending status forward. The `dev` receiver keeps the first version of a transaction (`ON CONFLICT DO NOTHING`), so a Pending transaction never completes;
  - decision 5: each failure is audited once per episode, with `occurred_at`. On `dev`, event IDs come from content that has no time, so the auditor drops every later outage with the same error text;
  - Security: the Cognito client secret in Secrets Manager, and TLS to transactions-db.
- 2026-10-14 10:10 +08:00: amended before acceptance, then accepted by the decision owner, subject to the Feature 3 owner's confirmation of decision 7 on this pull request. Lambda stays the reader's runtime; an ECS scheduled task remains the fallback (Option C).
  - **Decision 3:** retries happen within a run and skip host-key and authentication failures; the circuit breaker spans runs and keeps its state in memory, as failure episodes do.
  - **Decision 4:** a file with an extension other than `.json` is rejected and audited as unsupported, as [data-flow.md](../architecture/data-flow.md#how-the-specs-audit-requirements-are-met) already said, and `.tmp` uploads are skipped. A rejected file moves only after its event is written.
  - **Validation:** adds the retry and circuit breaker tests.
