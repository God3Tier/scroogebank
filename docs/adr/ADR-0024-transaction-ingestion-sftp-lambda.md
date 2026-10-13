# ADR-0024: Transaction ingestion: external SFTP server, scheduled VPC-attached Lambda, ready-file protocol, idempotent import

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: on acceptance, the runtime [ADR-0017](ADR-0017-microservices-per-business-area.md) gives transaction-service and transaction-ingest (decision 7)
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
   - Timeouts, retries and a circuit breaker protect the connection.
4. **Per file:**
   - verify that the file is a JSON array of transactions, and each row; a truncated or damaged file fails this check and is rejected with a `FILE_FORMAT_INVALID` event;
   - `INSERT ... ON CONFLICT (transaction_id) DO UPDATE` the status;
   - in the same transaction, write the file's `FILE_PROCESSING_COMPLETED` event to `audit_outbox` ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)), with its start and end times, status, record counts, and each rejected row's record number and reason; rejected rows are not kept in a table;
   - move the file to `processed/` or `rejected/`.

   There is no table of processed files. A file leaves `reports/` only after its import commits; if moving it fails, the next run reads it again, and the upsert stores no duplicate transactions.
5. **On failure:**
   - on a connection or authentication failure, write a `CONNECTION_FAILURE` event and stop;
   - files stay in `reports/` for the next run;
   - on a system exception, keep the file and write a `SYSTEM_EXCEPTION` event.

   Every ingestion event has event type `TRANSACTION_INGESTION`. A failure event holds the file name (empty for a connection failure), the stage and the error message. It has no import to join, so it is saved to `audit_outbox` on its own, and sent straight to SQS if that save fails.
6. **No Client ID check at import (provisional):** rows are validated on their own. Ownership is enforced when transactions are read ([open item 9](../architecture/open-items.md#9-client-id-validation-at-import), [open item 10](../architecture/open-items.md#10-how-transaction-reads-enforce-agent-ownership)).
7. **Runtime: Go**, as built on `dev`. The receiver module holds both the import and transaction-service's read API, so both Feature 3 components stay in Go. This extends the Go exception in [ADR-0017](ADR-0017-microservices-per-business-area.md), whose table lists them as Java 25; once accepted, it supersedes that runtime entry only. On Lambda, the import runs as a compiled binary on the OS-only runtime (`provided.al2023`) and does what one `-once` run does today. The Feature 3 owner confirms this before acceptance.

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
- A Lambda cannot use ECS Service Connect, so a Client ID lookup at import would need another route.
- A row whose Client ID matches no CRM client is stored rather than rejected. No agent can see it, and an optional reconciliation job can list such rows.
- Damage that still parses, such as one changed digit in an amount, is not detected unless the file format adds a control record ([open item 5](../architecture/open-items.md#5-sftp-file-format-and-schedule)).
- The `dev` receiver still needs a Lambda handler, the rejection of invalid and unsupported files, and retries with a circuit breaker on the connection (see Context). The field layout and the file schedule are still [open item 5](../architecture/open-items.md#5-sftp-file-format-and-schedule).

## Security & Privacy Impact

- Key-based authentication only, with the worker's private key in Secrets Manager.
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
  - a server outage (a `Failed` event, and the file picked up later);
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
