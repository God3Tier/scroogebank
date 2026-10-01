# ADR-0024: Transaction ingestion: external SFTP server, scheduled VPC-attached Lambda, ready-file protocol, idempotent import

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
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

The current `dev` implementation, `services/sftp/receiver`, differs from this design:
- a Go cron job, not a Lambda function;
- JSON files in `/reports`, not CSV files in `inbox/`;
- no `.sha256` checksum;
- `processed/` and `rejected/` archive folders;
- audit events sent straight to SQS, with no outbox.

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
2. **Ready-file protocol:** the generator uploads `name.csv.tmp`, writes a `.sha256` checksum, then renames the upload to its final name. The rename is atomic, so the reader never sees a half-written file.
3. **Reader:** `transaction-ingest`, a Lambda function in the private app subnets of both AZs.
   - EventBridge Scheduler invokes it about every 5 minutes.
   - Reserved concurrency is 1.
   - It connects out through the NAT gateway with key authentication and a pinned host key (in SSM Parameter Store).
   - Timeouts, retries and a circuit breaker protect the connection.
4. **Per file:**
   - skip it if already recorded in `ingestion_file` (name and SHA-256);
   - verify the checksum, format and each row;
   - `INSERT ... ON CONFLICT (transaction_id) DO UPDATE` the status;
   - record rejected rows with reasons;
   - in the same transaction, write the `FILE_PROCESSED` event to `ingest_outbox` ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md));
   - move the file to `processed/` or `rejected/`.
5. **On failure:**
   - on a connection or authentication failure, write a `Failed` `FILE_PROCESSED` event and stop;
   - files stay in `inbox/` for the next run;
   - on a system exception, keep the file and audit the error.
6. **No Client ID check at import (provisional):** rows are validated on their own. Ownership is enforced when transactions are read ([open item 9](../architecture/open-items.md#9-client-id-validation-at-import), [open item 10](../architecture/open-items.md#10-how-transaction-reads-enforce-agent-ownership)).
7. **Runtime:** Java on a Lambda managed runtime, or a container image if Java 25 is not yet a managed runtime. The Feature 3 owner confirms this, because `dev` has a Go reader; keeping Go would extend the runtime exception in [ADR-0017](ADR-0017-microservices-per-business-area.md).

## Rationale

- SFTP is how banks commonly exchange batch files, and SSH encrypts the data in transit.
- Self-hosting saves about USD 208–210/month against Transfer Family. The single-instance risk is acceptable because the server stands in for an external system, which the CRM must tolerate failing anyway.
- Lambda fits short, periodic batch work, and reserved concurrency of 1 prevents overlapping runs.

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
- The `dev` receiver must be aligned or this ADR changed. That covers the format, folder, checksum, outbox and runtime ([open item 5](../architecture/open-items.md#5-sftp-file-format-and-schedule)).

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
  - a corrupted file (checksum mismatch);
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
