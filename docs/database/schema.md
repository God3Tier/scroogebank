# Database design (target architecture)

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-13.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](../architecture/system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

## Databases

Recorded in [ADR-0023](../adr/ADR-0023-rds-per-service-backup-no-cache.md) (Proposed).

| Database | Owned by | Holds |
|---|---|---|
| `users-db` | user-service | CRM users (Admin/Agent), linked to Cognito; `audit_outbox` |
| `clients-db` | client-service | Client profiles, verification status with who verified and when, `deleted_at` for soft-deleted clients ([ADR-0036](../adr/ADR-0036-soft-deletion-of-clients-and-accounts.md)), and `client_documents`: one row per identity document ([ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md)) |
| `accounts-db` | account-service | Bank accounts, each with `owner_agent_id`: the owning agent's Cognito `sub`, copied from client-service when the account is opened ([ADR-0035](../adr/ADR-0035-account-ownership-across-services.md)), and `deleted_at` for soft-deleted accounts ([ADR-0036](../adr/ADR-0036-soft-deletion-of-clients-and-accounts.md)) |
| `transactions-db` | transaction-service and transaction-ingest | Imported transactions and `audit_outbox`, the only outbox, because on `dev` the SFTP receiver is both transaction-service and transaction-ingest. Each transaction stores the `account_id` and `client_id` that account-service confirmed at import, with no foreign keys ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)) |
| `audit-db` | auditor and chain-verify | The hash-chained `audit_log`, and the tamper-findings table (`corrupted_audit_log` on `dev`) |
| `client-keys` (DynamoDB) | client-service | Per-client data keys, KMS-wrapped, one-to-one with client UUIDs. Email and phone blind indexes in clients-db use a separate shared KMS HMAC key ([ADR-0030](../adr/ADR-0030-client-data-envelope-encryption.md)) |
| `alerts-db` | event-alert-service (api and worker) | Holdings (synthetic for the MVP), instrument master, account sector exposures, public events, alerts, agent decisions, outbox |
| `health-db` | client-health-monitor-service (API and analysis task) | Activity projection, analysis runs and watermarks, signals and evidence, tasks and status history, explanation records, outbox |

- **One RDS for PostgreSQL instance per service**, physically separate. Each service also keeps its **outbox table** in its own database. dashboard-service has no database ([X-factor 2: business dashboard](../architecture/x-factors.md#x-factor-2-business-dashboard)).
- **Instance size:** `db.t4g.micro` (2 vCPU burstable, 1 GiB memory), gp3 storage with storage auto-scaling. Each instance serves only one service's tasks (about 2–6 tasks × a pool of 10 connections).
- **Multi-AZ standby for every database (NFR 2, 9).** A synchronously replicated standby in the other AZ's isolated subnet; automatic failover, typically within one to two minutes. Services reconnect to the same endpoint.
- **No cross-service data access.** A service's security group and credentials reach only its own instance. Cross-service data is fetched through the owning service's API. The accounts-to-clients foreign key in today's `V1_init.sql` cannot cross databases; it is replaced by an API check when accounts are created, and by client-service refusing to delete a client who still has open accounts. Both checks forward the caller's token and fail closed ([ADR-0035](../adr/ADR-0035-account-ownership-across-services.md)).
- **Soft deletes in clients-db and accounts-db** ([ADR-0036](../adr/ADR-0036-soft-deletion-of-clients-and-accounts.md)). Deleting a client or account sets `deleted_at` and keeps the row; every API treats a deleted row as missing. "Open accounts" above means accounts that are not deleted, including accounts whose status is `closed`. Email, phone number and one-account-per-type are unique only among rows that are not deleted (partial unique indexes).
- **Encryption:** KMS at rest; TLS required on every database connection (`rds.force_ssl`).
- **Backups:** RDS automated backups with point-in-time recovery (RPO of minutes), plus a daily AWS Backup plan with a copy to ap-southeast-2 ([Why AWS Backup is included when RDS already has backups](#why-aws-backup-is-included-when-rds-already-has-backups)). The client-keys table has DynamoDB point-in-time recovery and is in the same AWS Backup plan: losing it would make all client data unreadable.
- **Consistency model (NFR 4):** strong (ACID) inside one service's database; eventual for the audit log (seconds behind) and for data that crosses services, such as imported transactions (per ingestion run).
- **Dashboard indexes** ([ADR-0038](../adr/ADR-0038-dashboard-metric-definitions.md)). The business dashboard's figures are computed in each owning database by date: `clients(created_at)`, `accounts(opening_date)` and `transactions(transaction_date)` are indexed for it. No tables or columns are added.
- **No cache tier.** Database indexes, CloudFront caching of static files and in-process caching (Cognito signing keys, reference data) are enough at project scale. The spec asks for caching only "if needed" (NFR 1).
- **Identity documents** (synthetic NRIC only) are uploaded through client-service, encrypted with the client's data key, and kept for audit in a private SSE-KMS S3 bucket ([ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md)). `client_documents` holds each document's ID, client, detected type, size, uploader and time, and whether it was a verification's evidence; never the file or its name. Uploading changes nothing else: the agent checks the documents and then verifies the client, which sets `verification_status`, `verified_by_agent_id` and `verified_at`.

## Why one RDS instance per service: balancing cost and isolation

Three database layouts were compared. Prices are rough ap-southeast-1 figures in USD/month; verify them with the AWS Pricing Calculator.

|   | A. One Aurora Serverless v2 cluster, a database per service | B. One Aurora Serverless v2 cluster per service | C. One RDS PostgreSQL instance per service (chosen) |
|---|---|---|---|
| Database cost, normal running | ~55–65 (one writer) | ~275–325 (five writers) | ~100–110 (five `db.t4g.micro`, single-AZ) |
| Database cost with standbys | ~110–125 | ~550–650 | ~200–220 (Multi-AZ) |
| Fits the USD 300 limit | Easily | **No**: the databases alone use it | Yes, with the lifecycle in [Environments and cost (target architecture)](../cost/budget.md) |
| Isolation between services | Logical only: shared compute, one failure domain, one maintenance window | Full | **Full** |
| One busy service slowing others | Possible until the cluster scales | No | **No** |
| Database compute auto-scaling (NFR 1) | Automatic | Automatic, per service | Manual resize (see trade-offs) |
| Setup | Needs a bootstrap task to create databases and users | Each cluster creates its own database | **Each instance creates its own database** |

**Decision: C.** It is the only option that gives every microservice its own database server **and** fits inside the USD 300 limit. The comparison was made for the five core databases; the x-factor databases (alerts-db, health-db) follow the same option and add roughly USD 26–30/month single-AZ ([Environments and cost (target architecture)](../cost/budget.md)).

- **Isolation (why not A):** each service gets its own failure domain, maintenance window, compute, storage, credentials and security group. A slow query, a runaway migration or a failure in one service's database cannot affect another service.
- **Cost (why not B):** Aurora Serverless v2 has a minimum charge of 0.5 ACU per instance that cannot be avoided while services hold open connections. Five clusters would cost more than the whole project budget; five `db.t4g.micro` instances give the same isolation for roughly a third of the price.
- **Simplicity:** each instance creates its own database from Terraform, so no bootstrap task is needed.

**Trade-offs accepted:**

- **Database compute does not auto-scale.** RDS scales storage automatically, but changing compute means resizing the instance, which causes a brief failover when Multi-AZ is on. NFR 1 is met for the database layer through storage auto-scaling, independent per-service resizing (only the busy service's instance), and an optional read replica for a read-heavy service such as transactions-db when load tests show a need. The ADR must state plainly that compute scaling is a manual resize.
- **Burstable instances.** `db.t4g.micro` runs on CPU credits; sustained heavy load can use them up. The size is raised if load tests show this.
- **Standbys cannot serve reads.** They exist only for failover.
- **More resources to run.** Seven instances mean seven sets of backups, alarms and maintenance windows; one reusable Terraform module keeps this manageable.
- **Tighter budget.** The databases cost roughly twice option A, so the environment lifecycle in [Environments and cost (target architecture)](../cost/budget.md) must be followed.

## Why AWS Backup is included when RDS already has backups

Each RDS instance has built-in automated backups with point-in-time recovery inside ap-southeast-1. AWS Backup is still chosen because it adds three things the spec asks for, across all seven databases at once:

- **A cross-region copy** of each daily snapshot to a backup vault in ap-southeast-2 (not drawn; [Parts of the architecture not shown on the diagram](../architecture/system-overview.md#parts-of-the-architecture-not-shown-on-the-diagram)). The spec's Availability NFR says "Cross region if needed", and the Resiliency NFR recommends a 12-hour RTO and RPO. If ap-southeast-1 is unavailable, the stack is rebuilt in ap-southeast-2 with Terraform and restored from the copies.
- **A backup and restore strategy defined in code** (NFR 2): one plan, selecting databases by tag, with schedule, retention and copy rules. A new service's database is covered as soon as it is tagged.
- **Protected backups:** Backup Vault Lock stops anyone deleting or shortening the retention of recovery points, which supports the tamper-resistance argument for audit data.

Cost is small: roughly USD 1–3/month for snapshot storage and cross-region transfer at this size. If the team decides cross-region recovery is out of scope, AWS Backup can be removed and RDS point-in-time recovery used alone; the ADR must then state that recovery from a regional outage is not supported.
