# ADR-0022: Audit tamper resistance (hash chain, chain-verify task, S3 Object Lock anchor) and GDPR crypto-shredding

- Status: Accepted
- Decision date/time: 2026-10-12 23:04 +08:00 (Asia/Singapore); first proposed 2026-10-01 21:50 +08:00
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture` (proposed); `task/governance/accept-adr-0022` (amended, accepted); implementation on `task/base-features/audit-hash-chain`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

The spec requires audit logs to be tamper-resistant and accessible to administrators, and NFR 8 requires GDPR compliance, including the right to erasure. The threat is someone changing the audit history after the fact to hide what they did: an agent or admin covering misuse, an attacker with stolen database credentials, a DBA with direct access, or a bug that corrupts old rows. Erasure conflicts with an append-only log unless personal data in it can be made unreadable without changing the log.

The auditor on `dev` already stores `parent_hash` and `hash` on each `audit_log` row and has a chain-verify routine, but it differs from this design ([open item 13](../architecture/open-items.md#13-tamper-findings-and-chain-verify-on-dev)):

- chain-verify runs on in-process timers, not as a scheduled task. It cannot be built either: `src/cronjob` is not a `main` package;
- on a mismatch it deletes the row, re-links the chain, and moves the row to `corrupted_audit_log`;
- it writes an "Error logging" event into the chain;
- no chain head is anchored in S3;
- tamper findings are served over REST on the auditor, including a delete.

The `dev` hash input also leaves out `parent_hash` and `event_id` and joins fields without separators. Editing one row therefore needs only that row and the next row's `parent_hash` to change, not every later hash. Verification also compares each recomputed hash only with the next row's `parent_hash`, and never checks the newest row.

Three more problems on `dev`:

- **The payload is hashed as received but stored as `jsonb`,** which reorders keys and adds spaces (`{"status_code":200,"agent_id":"AGT-1"}` is stored as `{"agent_id": "AGT-1", "status_code": 200}`). Recomputing a hash from the stored row therefore fails for almost every entry. A working chain-verify would delete most of the audit log.
- **Timestamps are `TIMESTAMP` without a time zone.** They are hashed at second precision in the writer's time zone, but read back as UTC.
- **The writer locks the newest row with `SELECT … FOR UPDATE`,** which needs `UPDATE` privilege, and finds that row by scanning the whole table.

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

1. **Hash chain:** each `audit_log` row stores `parent_hash` (the previous row's hash, empty for the first row) and its own `hash`. Changing or deleting any past row therefore breaks every later hash.
   - **Encoding:** the hash is SHA-256, in lowercase hex, of:
     - a version tag, `audit-chain-v1`;
     - then the row's fields in this order: `id`, `sequence_no`, `event_id`, `event_type`, `action`, `source_service`, `actor_id`, `timestamp`, `payload` and `parent_hash`.

     Each item is written as its length in bytes, a colon, its bytes and a comma (`5:hello,`), so two different rows can never encode the same way. A new encoding gets a new version tag.
   - **Timestamps** are `TIMESTAMPTZ`, written in UTC at microsecond precision (as Postgres stores them), and hashed as `2026-10-12T15:04:05.123456Z`.
   - **The payload is stored as `json`, not `jsonb`,** so the stored text is exactly the text that was hashed.
   - **`event_id`** is NULL for an entry without one and never the empty string, so the two cannot be swapped.
   - **Sequence numbers** start at 1 and have no gaps. Rows are never updated or deleted.
   - **Writers take turns** through a transaction-scoped advisory lock and append after the highest `sequence_no`. An advisory lock needs no privilege on `audit_log` (decision 2).
2. **Least privilege:**
   - **The auditor's database role** has only `INSERT` and `SELECT` on `audit_log`, with no `UPDATE`, `DELETE` or `TRUNCATE`. On the findings table it has `SELECT`, `INSERT` and `DELETE` while the admin endpoints exist (decision 4).
   - **chain-verify's database role** has `SELECT` on `audit_log`, and `SELECT` and `INSERT` on the findings table.
   - **Neither role owns the tables;** the schema-migration task does ([ADR-0028](ADR-0028-cicd-migrations-terraform.md)).
3. **chain-verify** is a separate program in the auditor's image. It checks the chain once and exits, and runs as its own ECS task started **daily by EventBridge Scheduler**. It:
   - reads every row in sequence order and checks:
     - that the row's fields produce its stored hash;
     - that its `parent_hash` is the previous row's hash (empty for the first row);
     - that its `sequence_no` is one more than the previous row's (1 for the first row);
   - compares the head with the latest S3 anchor;
   - writes the new chain head (`sequence_no`, `hash`) to an **S3 Object Lock** bucket (governance mode, short retention so the project can be torn down);
   - records each problem it finds as a finding (decision 4);
   - raises an alarm on each new finding:
     - it logs each one as `CHAIN_TAMPER_DETECTED`, for a CloudWatch metric filter;
     - it exits with a failure status when it records a new finding, or when the check cannot finish.

     A problem already recorded on an earlier run is not alarmed on again.

   chain-verify never writes to `audit_log`. Only chain-verify's role can write to the anchor bucket. **The S3 anchor is built in a later change than the rest of this ADR;** until then, a full rewrite of the chain, or removing its newest rows, goes undetected.
4. **Tamper findings** go to a separate table in audit-db (`corrupted_audit_log`). Each finding holds:
   - the affected row as chain-verify found it;
   - the row's id;
   - the reason: `HASH_MISMATCH`, `PARENT_MISMATCH` or `SEQUENCE_GAP`;
   - the detection time.

   The row's id and the reason are unique together, so a problem is recorded once, when it is first found, rather than on every daily run. The chain itself is never rewritten.

   Admins reach the findings through user-service, never from the auditor directly ([ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md) decision 4). Which operations they have on findings (read only, or also create and delete, as on `dev`) and how they are served are still open here ([open item 13](../architecture/open-items.md#13-tamper-findings-and-chain-verify-on-dev)). Until that is decided, `dev`'s create and delete stay. A finding created through the endpoint has no row id or reason.
5. **Crypto-shredding for erasure:**
   - a client's erasure hard-deletes their data in every owning service and deletes their data key from client-keys ([ADR-0030](ADR-0030-client-data-envelope-encryption.md));
   - audit payloads with personal data are encrypted with a per-client key, and the chain hashes the ciphertext, so verification still passes after erasure ([open item 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data));
   - erasure is complete only after the 35-day point-in-time recovery window on client-keys and the backup retention have passed.

## Rationale

- CloudTrail cannot see business events, and the managed ledger option is retired, so the CRM needs its own tamper evidence. This applies the same technique CloudTrail uses for its own log files.
- The S3 anchor closes the "recompute every hash" gap.
- Length-prefixed fields leave no room for ambiguity and need no escaping. Hashing the stored text (`json`, microsecond UTC timestamps) means verification recomputes exactly what was written.
- Recording each problem once keeps the findings readable: a broken row stays broken, because the chain is never repaired.
- Running verification as a scheduled task means:
  - one run a day, not one per auditor copy;
  - a reliable schedule;
  - no load on the live service;
  - a separate least-privilege role for writing anchors.

## Consequences

### Positive

- Edits, deletions, insertions and full rewrites of the audit history are detected.
- A compromised or faulty auditor can add audit entries but cannot change or delete them.
- Erasure works without breaking verification.
- CloudTrail complements the chain by recording changes to the anchor bucket, audit-db and chain-verify's role.

### Negative / Trade-offs

- The chain is tamper-**evident**, not tamper-proof; prevention relies on database permissions.
- Rows after the last anchor (up to a day) can be truncated undetected. Until the anchor is built, so can the whole chain be rewritten.
- The chain proves a row is unchanged since it was written, not that the event was true.
- A found problem cannot be fixed in the chain, only investigated; its finding stays.
- The new encoding invalidates chains written by `dev`'s code. Nothing is deployed, so only local databases are recreated.
- A `json` payload is parsed again for each activity-feed filter, which is slower than `jsonb`; this is acceptable at the CRM's volume.

## Security & Privacy Impact

Object Lock in governance mode can be bypassed only by principals with `s3:BypassGovernanceRetention`; restrict that permission and watch it with CloudTrail. Backup Vault Lock protects the audit-db backups ([ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md)).

## Cost Impact

The anchor bucket stores one small object per day (negligible). chain-verify is billed for a few minutes per day.

## Operational Impact

- A chain-mismatch alarm through SNS, from a metric filter on `CHAIN_TAMPER_DETECTED` or from chain-verify's failed task.
- A runbook for investigating findings.
- In Terraform:
  - the chain-verify task definition, which runs chain-verify instead of the auditor from the same image;
  - its EventBridge schedule;
  - a database login per role in decision 2.
- Object Lock retention chosen so the bucket can be deleted at project end.

## Validation

- A test that edits a past row (with and without recomputing hashes) and checks chain-verify reports it.
- A test that deletes a row in the middle and checks the link break is reported.
- A test that a problem found again on a later run is not recorded or alarmed on twice.
- A test that the auditor's role can append entries but cannot update, delete or truncate them.
- A test that concurrent writers produce one chain with no gaps.
- A test that rewrites the whole chain and checks the S3 anchor comparison catches it (with the anchor).
- A test that erases a client and checks verification still passes (with erasure).

## Revision history

- 2026-10-01 21:50 +08:00: proposed.
- 2026-10-12 23:04 +08:00: amended before acceptance, then accepted by the decision owner.
  - **Context:** adds three problems found in `dev`'s code. The payload is hashed as received but stored as `jsonb`. Timestamps have no time zone. The writer's row lock needs `UPDATE` privilege.
  - **Decision 1:** fixes the encoding, the timestamp precision, `json` payloads and the advisory lock.
  - **Decision 2:** adds chain-verify's database role.
  - **Decision 3:** states:
    - what each row is checked against;
    - that chain-verify runs once and never writes to the chain;
    - how it alarms;
    - that the S3 anchor comes in a later change.
  - **Decision 4:** adds the finding's row id and reason, each problem recorded once.

  Hayyun, who owns the auditor's code, is asked to agree on the implementation's pull request.

## References

- [Audit integrity and GDPR](../security/security-model.md#audit-integrity-and-gdpr)
- [Why chain-verify is a separate task](../security/security-model.md#why-chain-verify-is-a-separate-task-not-part-of-the-auditor-service)
- [Open item 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data), [open item 13](../architecture/open-items.md#13-tamper-findings-and-chain-verify-on-dev)
- [ADR-0028](ADR-0028-cicd-migrations-terraform.md) (schema-migration task)
- `services/auditor/src/internal/repository/audit/store_audit_chain.go`, `services/auditor/src/internal/service/audit/verify_chain.go` (`dev` behaviour before the implementation)
- AWS documentation: S3 Object Lock; CloudTrail log file integrity validation
