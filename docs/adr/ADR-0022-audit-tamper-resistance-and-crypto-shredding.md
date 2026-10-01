# ADR-0022: Audit tamper resistance (hash chain, chain-verify task, S3 Object Lock anchor) and GDPR crypto-shredding

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

The spec requires audit logs to be tamper-resistant and accessible to administrators, and NFR 8 requires GDPR compliance, including the right to erasure. The threat is someone changing the audit history after the fact to hide what they did: an agent or admin covering misuse, an attacker with stolen database credentials, a DBA with direct access, or a bug that corrupts old rows. Erasure conflicts with an append-only log unless personal data in it can be made unreadable without changing the log.

The auditor on `dev` already stores `parent_hash` and `hash` on each `audit_log` row and has a chain-verify routine, but it differs from this design ([open item 13](../architecture/open-items.md#13-tamper-findings-and-chain-verify-on-dev)):

- chain-verify runs on in-process timers, not as a scheduled task;
- on a mismatch it deletes the row, re-links the chain, and moves the row to `corrupted_audit_log`;
- it writes an "Error logging" event into the chain;
- no chain head is anchored in S3;
- tamper findings are served over REST on the auditor, including a delete.

The `dev` hash input also leaves out `parent_hash` and `event_id` and joins fields without separators. Editing one row therefore needs only that row and the next row's `parent_hash` to change, not every later hash. Verification also compares each recomputed hash only with the next row's `parent_hash`, and never checks the newest row.

## Decision Drivers

- Tamper evidence that survives an attacker with database access
- Least privilege
- GDPR erasure without breaking verification
- Cost and the ability to tear the project down

## Options Considered

### Option A: rely on AWS CloudTrail

- Pros: managed; CloudTrail's own log files have integrity validation (hash chain plus AWS-signed digests).
- Cons: CloudTrail records AWS API calls, not business events. SQL reads and writes go straight to the database over port 5432, so CloudTrail never sees them, and it cannot know which agent acted (services use one database login).

### Option B: CloudTrail Lake custom events or a managed ledger

- Pros: an immutable managed store.
- Cons: Amazon QLDB reached end of support in July 2025. CloudTrail Lake adds per-GB ingestion and per-query cost, its current availability and pricing need checking, and an immutable store cannot erase personal data.

### Option C: hash chain only

- Pros: simple; detects edits made without recomputing hashes.
- Cons: someone with database access can rewrite rows and recompute every hash, which goes undetected.

### Option D: hash chain, separate chain-verify task, S3 Object Lock anchor, tamper-findings table (chosen)

- Pros: detects edits, deletions, insertions and full rewrites; the anchor cannot be changed even by a database administrator.
- Cons: more moving parts; rows written after the last anchor can be truncated undetected until the next anchor.

## Decision

1. **Hash chain:** each `audit_log` row stores `parent_hash` (the previous row's hash) and its own `hash`. The hash covers a canonical, unambiguous encoding of all the row's fields, including `event_id` and `parent_hash`. Changing or deleting any past row therefore breaks every later hash. Timestamps are stored as `TIMESTAMPTZ` in UTC.
2. **Least privilege:** the auditor's database role has only `INSERT` and `SELECT` on `audit_log`.
3. **chain-verify** is a separate ECS task, built from the auditor's image and started **daily by EventBridge Scheduler**. It:
   - recomputes and checks every row, including each row's own stored hash and the newest row;
   - compares the head with the latest S3 anchor;
   - writes the new chain head (`sequence_no`, `hash`) to an **S3 Object Lock** bucket (governance mode, short retention so the project can be torn down);
   - raises an alarm on any mismatch.

   Only chain-verify's role can write to the anchor bucket.
4. **Tamper findings** go to a separate table in audit-db, with the affected row and the detection time. The chain itself is never rewritten. Admins read the findings through user-service, which calls the auditor over gRPC ([ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md)).
5. **Crypto-shredding for erasure:**
   - a client's erasure hard-deletes their data in every owning service and deletes their data key from client-keys ([ADR-0030](ADR-0030-client-data-envelope-encryption.md));
   - audit payloads with personal data are encrypted with a per-client key, and the chain hashes the ciphertext, so verification still passes after erasure ([open item 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data));
   - erasure is complete only after the 35-day point-in-time recovery window on client-keys and the backup retention have passed.

## Rationale

- CloudTrail cannot see business events, and the managed ledger option is retired, so the CRM needs its own tamper evidence. This applies the same technique CloudTrail uses for its own log files.
- The S3 anchor closes the "recompute every hash" gap.
- Running verification as a scheduled task means:
  - one run a day, not one per auditor copy;
  - a reliable schedule;
  - no load on the live service;
  - a separate least-privilege role for writing anchors.

## Consequences

### Positive

- Edits, deletions, insertions and full rewrites of the audit history are detected.
- Erasure works without breaking verification.
- CloudTrail complements the chain by recording changes to the anchor bucket, audit-db and chain-verify's role.

### Negative / Trade-offs

- The chain is tamper-**evident**, not tamper-proof; prevention relies on database permissions.
- Rows after the last anchor (up to a day) can be truncated undetected.
- The chain proves a row is unchanged since it was written, not that the event was true.
- The `dev` auditor needs changes to match this design: the hash input, verification, no deletes, a scheduled task, the anchor, and read-only findings.

## Security & Privacy Impact

Object Lock in governance mode can be bypassed only by principals with `s3:BypassGovernanceRetention`; restrict that permission and watch it with CloudTrail. Backup Vault Lock protects the audit-db backups ([ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md)).

## Cost Impact

The anchor bucket stores one small object per day (negligible). chain-verify is billed for a few minutes per day.

## Operational Impact

- A chain-mismatch alarm through SNS.
- A runbook for investigating findings.
- The chain-verify task definition and EventBridge schedule in Terraform.
- Object Lock retention chosen so the bucket can be deleted at project end.

## Validation

- A test that edits a past row (with and without recomputing hashes) and checks chain-verify reports it.
- A test that deletes a row in the middle and checks the link break is reported.
- A test that rewrites the whole chain and checks the S3 anchor comparison catches it.
- A test that erases a client and checks verification still passes.

## References

- [Audit integrity and GDPR](../security/security-model.md#audit-integrity-and-gdpr)
- [Why chain-verify is a separate task](../security/security-model.md#why-chain-verify-is-a-separate-task-not-part-of-the-auditor-service)
- [Open item 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data), [open item 13](../architecture/open-items.md#13-tamper-findings-and-chain-verify-on-dev)
- `services/auditor/src/internal/repository/audit/store_audit_chain.go`, `services/auditor/src/internal/service/audit/verify_chain.go` (current `dev` behaviour)
- AWS documentation: S3 Object Lock; CloudTrail log file integrity validation
