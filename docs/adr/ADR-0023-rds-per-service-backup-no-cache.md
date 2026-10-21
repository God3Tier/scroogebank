# ADR-0023: One RDS PostgreSQL instance per service with a Multi-AZ standby, AWS Backup cross-region copy, no cache tier

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`; deployment status added on `task/transaction-integration/ingest-lambda`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

Each microservice owns its data ([ADR-0017](ADR-0017-microservices-per-business-area.md)). Seven services need relational ACID storage: user, client, account, transaction, auditor, event-alert and health-monitor. dashboard-service has no database. The spec asks for a relational ACID database (Feature 2), redundancy and failover across AZs (NFR 2), durability (NFR 9), a backup and restore strategy with a recommended 12-hour RTO and RPO (NFR 5), and caching only "if needed" (NFR 1). Spend must stay under USD 300.

[`nfr-notes.md`](../architecture/nfr-notes.md) already records one RDS instance per service, so this ADR formalises it. It adds the backup and cache decisions; the cross-region copy differs from that note's single-region stance.

## Decision Drivers

- Isolation between services (failure domain, maintenance, credentials)
- Cost within the USD 300 limit
- Availability and durability (NFR 2, NFR 5, NFR 9)
- Simplicity of provisioning

## Options Considered

Rough ap-southeast-1 prices, USD/month, for the five core databases (verify with the AWS Pricing Calculator):

| | A. One Aurora Serverless v2 cluster, a database per service | B. One Aurora Serverless v2 cluster per service | C. One RDS PostgreSQL instance per service (chosen) |
|---|---|---|---|
| Normal running | ~55–65 | ~275–325 | ~100–110 (single-AZ `db.t4g.micro`) |
| With standbys | ~110–125 | ~550–650 | ~200–220 (Multi-AZ) |
| Fits USD 300 | Easily | No | Yes, with the lifecycle in [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md) |
| Isolation | Logical only | Full | Full |
| Compute auto scaling | Automatic | Automatic | Manual resize |

**Backups:** RDS point-in-time recovery only, or with AWS Backup and a cross-region copy as well.
**Caching:** ElastiCache, no cache tier, or in-process caches where needed.

## Decision

1. **Option C.** One RDS for PostgreSQL instance per service:
   - `db.t4g.micro`, gp3 storage with storage auto scaling;
   - a **Multi-AZ standby** for every database (switched on in the high-availability demo week, [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md));
   - KMS encryption at rest and `rds.force_ssl`;
   - each database's security group allows port 5432 only from its owning service.
2. **No cross-service data access.** Cross-service data goes through the owning service's API. The accounts-to-clients foreign key is replaced by an API check.
3. **Backups:**
   - RDS automated backups with point-in-time recovery, for an RPO of minutes;
   - one tag-based **AWS Backup** daily plan covering all seven databases and the DynamoDB client-keys table;
   - a **copy to a vault in ap-southeast-2**, protected by Backup Vault Lock;
   - RTO under 12 hours by rebuilding the stack in ap-southeast-2 with Terraform and restoring from the copies.
4. **No cache tier.** Database indexes, CloudFront caching of static files and in-process caches (Cognito signing keys, reference data, the dashboard's 3-minute cache, [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md)) are enough at project scale.

## Deployment status

The team's AWS account cannot deploy decision 3's cross-region copy. Its service control policy denies `backup:CreateBackupVault` in ap-southeast-2, so [ADR-0042](ADR-0042-terraform-deployment.md) decision 11 leaves out the Sydney vault, its KMS key, its Vault Lock and the backup plan's copy. The daily AWS Backup plan and RDS point-in-time recovery are deployed, in ap-southeast-1 only.

The policy is set by the organisation that manages the account, not by the team, and it may never be lifted. Until it is:

- recovery from the loss of ap-southeast-1 is not supported, because no copy exists outside it;
- no Vault Lock protects the recovery points, so they can be deleted or have their retention shortened;
- the restore rehearsal in Validation can restore only within ap-southeast-1.

Point-in-time recovery and restores within ap-southeast-1 are unaffected.

If the policy is still in place when the deployment is final, Operational Impact applies: this ADR must be superseded with a statement that regional-outage recovery is not supported, unless the decision owner finds another way to keep it. Two options, neither checked against the policy yet:

- copy to a different Region that the policy allows;
- keep backups in ap-southeast-1 only, with Vault Lock on the primary vault, which keeps protected recovery points but not regional recovery.

## Rationale

- Option C is the only layout that gives every service its own database server **and** fits the budget.
- Each instance creates its own database from Terraform, so no bootstrap task is needed.
- AWS Backup adds what RDS alone lacks:
  - a cross-region copy (the Availability NFR says "cross region if needed");
  - a backup strategy defined in code;
  - deletion-protected recovery points.

  It costs about USD 1–3/month.
- ElastiCache would add a billable cluster and a failure point without a measured need.

## Consequences

### Positive

- Full isolation: a slow query or a bad migration in one database cannot affect another service.
- Recovery from an AZ failure (automatic failover) and from a regional outage (rebuild from the copies). The second is not available while the account refuses the copy ([Deployment status](#deployment-status)).

### Negative / Trade-offs

- **Database compute does not auto-scale.** Changing compute is a manual resize, with a brief failover when Multi-AZ is on. NFR 1 is met at the database layer through:
  - storage auto scaling;
  - per-service resizing;
  - an optional read replica for a read-heavy service when load tests show a need.
- Burstable instances run on CPU credits; resize if load tests show credit exhaustion.
- Standbys cannot serve reads.
- Seven sets of backups, alarms and maintenance windows (one reusable Terraform module).
- The databases cost roughly twice option A, so the environment lifecycle is mandatory.

## Security & Privacy Impact

Separate credentials (RDS-managed master passwords in Secrets Manager) and security groups per database; TLS on every connection; databases in isolated subnets. Backup Vault Lock stops anyone deleting or shortening recovery points, which supports the audit tamper-resistance argument. The deployed backups have no Vault Lock while the account refuses the vault ([Deployment status](#deployment-status)).

## Cost Impact

Seven `db.t4g.micro` instances cost about USD 140–155/month single-AZ, or 280–310 Multi-AZ, if left on 24/7. AWS Backup adds about USD 1–3/month. The lifecycle in [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md) keeps the actual spend inside the limit. Fallback lever: one shared x-factor instance for alerts-db and health-db, which saves about USD 13–15/month at the cost of isolation.

## Operational Impact

- RDS alarms (CPU, credits, storage, connections).
- A backup plan defined in Terraform.
- A restore runbook and rehearsal.
- If cross-region recovery is dropped, this ADR must be superseded with a statement that regional-outage recovery is not supported.

## Validation

- **Failover drill:** reboot with failover on a service's database in the demo week, and record the recovery time.
- **Restore rehearsal:** rebuild the stack in ap-southeast-2 from the AWS Backup copies within the 12-hour RTO. While the account refuses the copy, rehearse a restore within ap-southeast-1 instead ([Deployment status](#deployment-status)).
- A test showing one service's credentials cannot connect to another's database.

## References

- [Database design](../database/schema.md)
- [Why one RDS instance per service](../database/schema.md#why-one-rds-instance-per-service-balancing-cost-and-isolation)
- [Why AWS Backup is included](../database/schema.md#why-aws-backup-is-included-when-rds-already-has-backups)
- [`nfr-notes.md`](../architecture/nfr-notes.md): "One RDS instance per service" (agrees) and "Single-region, Multi-AZ only" (differs)

## Revision history

- 2026-10-01 21:50 +08:00: proposed.
- 2026-10-16: deployment status added while Proposed, as decided by the decision owner, after the account's service control policy refused the ap-southeast-2 vault and noting that the policy may never be lifted. Decision 3 is unchanged as the target.
