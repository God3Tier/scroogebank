# Database schema migrations (target architecture)

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-20.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](../architecture/system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

## Schema migrations (db-migrate)

Recorded in [ADR-0028](../adr/ADR-0028-cicd-migrations-terraform.md) decision 3 (Proposed).

Terraform creates each RDS instance with an empty database, and each service owns its versioned migrations:

- **user-service and event-alert-service** run Flyway on startup, from `src/main/resources/db/migration`. Flyway locks the database while it migrates, so this is safe with several tasks.
- **client-service, account-service, the auditor and transaction-service (the SFTP receiver)** keep their migrations in their `data/` directory. In AWS:
  - each task's `db-migrate` init container applies them before the app starts, and the app starts only if it succeeds;
  - the container runs Flyway 11.7.2 from the db-migrate image ([`infrastructure/db-migrate/`](../../infrastructure/db-migrate/Dockerfile)), as that database's master user, against that database only;
  - no other container receives the master credentials.
- **Locally, the same image applies them to each service's own Compose database**, so a local database behaves as a deployed one:
  - client-service, account-service and the auditor run it through `make run` or `make migrate`;
  - the SFTP stack's `db-migrate` service runs it before `receiver-api` starts, and `make db-up` runs it too.

  A local database loaded with `psql` before this has no schema history, so Flyway refuses it: recreate it (`make db-reset`, or for the SFTP stack the commands `make db-up` prints).
- **The throwaway databases that `make sqlboiler` and `make test` create** still load the files with `psql`. They start empty every time, so Flyway would have nothing to check.
- **audit-db's run also creates the logins** `auditor_app` and `chain_verify`, and sets their passwords from Secrets Manager every time ([`afterMigrate.sql`](../../infrastructure/db-migrate/audit/afterMigrate.sql), [ADR-0022](../adr/ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) decision 2).
- **Each task's `db-migrate` log stream** in the service's log group shows what Flyway applied, or why it stopped.

### Adding a migration to a Go service

1. **Add a new file** to the service's `data/` directory, named `V<n>_<description>.sql`, with `<n>` one more than the highest version. Each version is a whole number.
   - The receiver's `init.sql` and the auditor's `schema.sql` are version 1: the image renames them.
   - Flyway ignores files with other names.
2. **Never edit a file after it has been applied.**
   - Flyway records each applied file's checksum, and stops with `Migration checksum mismatch` if the file changes.
   - Locally, `make run` stops with that error. In AWS, the task never starts its app, and the deployment rolls back.
   - Line endings don't count, so Windows and Linux checkouts give the same checksum.
3. **Update the throwaway databases.** `make run` applies the new file locally on its own. `make sqlboiler` and `make test` load one schema file (`SCHEMA_FILE`, or the SFTP stack's `test-db` mount) and generate the sqlboiler models from it. Make them apply the new file after that one.
4. **Build and push the db-migrate image** with the services' images ([deploy-aws.md](../runbooks/deploy-aws.md) steps 2 and 5).
