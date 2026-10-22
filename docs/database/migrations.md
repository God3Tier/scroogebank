# Database schema migrations (target architecture)

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-20.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](../architecture/system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

## Schema migrations (db-migrate)

Recorded in [ADR-0028](../adr/ADR-0028-cicd-migrations-terraform.md) decision 3 (Proposed).

Terraform creates each RDS instance with an empty database, and each service owns its versioned migrations:

- **client-service, account-service, the auditor and transaction-service (the SFTP receiver)** keep their migrations in their `data/` directory, named `V<n>_<description>.sql`.
- **user-service, event-alert-service and client-health-monitor-service** keep
  theirs in `src/main/resources/db/migration`, named
  `V<n>__<description>.sql`. Locally and in tests Flyway applies them; Client
  Health migration tests run V1 task persistence followed by V2 evidence
  constraint tightening against PostgreSQL 17.
- **In AWS, every one of these services' tasks has a `db-migrate` init container:**
  - it applies the service's migrations before the app starts, and the app starts only if it succeeds;
  - it runs Flyway 11.7.2 from the db-migrate image ([`infrastructure/db-migrate/`](../../infrastructure/db-migrate/Dockerfile)), as that database's master user, against that database only. Flyway locks the database while it migrates, so tasks that start together are safe;
  - no other container receives the master credentials;
  - user-service's and event-alert-service's own Flyway is off there (`SPRING_FLYWAY_ENABLED=false`), and their db-migrate uses Spring Boot's `__` separator, so their history matches what the apps already wrote.
- **Locally, the same image applies the Go services' migrations to each one's own Compose database**, so a local database behaves as a deployed one:
  - client-service, account-service and the auditor run it through `make run` or `make migrate`;
  - the SFTP stack's `db-migrate` service runs it before `receiver-api` starts, and `make db-up` runs it too.

  A local database loaded with `psql` before this has no schema history, so Flyway refuses it: recreate it (`make db-reset`, or for the SFTP stack the commands `make db-up` prints).
- **The throwaway databases that `make sqlboiler` and `make test` create** still load the files with `psql`. They start empty every time, so Flyway would have nothing to check.
- **Each task's `db-migrate` log stream** in the service's log group shows what Flyway applied, or why it stopped.

### Database logins

No service connects as its database's master user. After every migrate, db-migrate runs its database's `afterMigrate.sql`, which creates the service's own login and sets its password from Secrets Manager. The master user owns the tables, so a login can't change the schema or create roles.

| Database | Login | Can | Callback |
|---|---|---|---|
| users, clients, accounts, alerts | `user_service`, `client_service`, `account_service`, `event_alert_service` | read and change rows in every table except `flyway_schema_history` | [`app-login/afterMigrate.sql`](../../infrastructure/db-migrate/app-login/afterMigrate.sql) |
| transactions | `transaction_service` | read `transactions`, since it checks no token | [`transactions/afterMigrate.sql`](../../infrastructure/db-migrate/transactions/afterMigrate.sql) |
| transactions | `transaction_ingest` (the ingest Lambda) | read and change rows in every table except `flyway_schema_history` | the same |
| audit | `auditor_app`, `chain_verify` | the roles in [ADR-0022](../adr/ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) decision 2 | [`audit/afterMigrate.sql`](../../infrastructure/db-migrate/audit/afterMigrate.sql) |

- **A new table needs no grant.** The grants run on every migrate, so a table that a migration adds is granted before the app starts. A table created any other way is not, and the app gets `permission denied` until the next task start.
- **The passwords are Flyway placeholders.** In AWS, ECS fills them from each login's secret. Locally, the Go services' Compose files set throwaway values. The local apps still connect as the database's owner.
- **The database console** (`scripts/db-run-sql.sh`) still runs SQL as the master user.

### Adding a migration

**user-service, event-alert-service and client-health-monitor-service:** add
`V<n>__<description>.sql` to `src/main/resources/db/migration`, as before.
Flyway applies it locally and in tests. Never edit one that has been applied.
To deploy it, build and push the db-migrate image with the service's image
([deploy-aws.md](../runbooks/deploy-aws.md) steps 2 and 5): the service's own
image doesn't apply it in AWS.

**The Go services:**

1. **Add a new file** to the service's `data/` directory, named `V<n>_<description>.sql`, with `<n>` one more than the highest version. Each version is a whole number.
   - The receiver's `init.sql` and the auditor's `schema.sql` are version 1: the image renames them.
   - Flyway ignores files with other names.
2. **Never edit a file after it has been applied.**
   - Flyway records each applied file's checksum, and stops with `Migration checksum mismatch` if the file changes.
   - Locally, `make run` stops with that error. In AWS, the task never starts its app, and the deployment rolls back.
   - Line endings don't count, so Windows and Linux checkouts give the same checksum.
3. **Update the throwaway databases.** `make run` applies the new file locally on its own. `make sqlboiler` and `make test` load one schema file (`SCHEMA_FILE`, or the SFTP stack's `test-db` mount) and generate the sqlboiler models from it. Make them apply the new file after that one.
4. **Build and push the db-migrate image** with the services' images ([deploy-aws.md](../runbooks/deploy-aws.md) steps 2 and 5).
