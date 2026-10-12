# Database schema migrations (target architecture)

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-12.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](../architecture/system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

## Schema migrations (db-migrate)

Recorded in [ADR-0028](../adr/ADR-0028-cicd-migrations-terraform.md) (Proposed).

- Terraform creates each RDS instance with an empty database. Each service owns its versioned migrations: Flyway for Java, golang-migrate for Go.
- **db-migrate** on the diagram stands for **one migration task per service**, using that service's image or a small migration image, against only that service's database. No shared job touches several databases.
- Running migrations as a separate task before deployment means the application's database user needs no rights to change the schema, and a bad migration stops the pipeline before any new task starts.
- Today user-service runs Flyway on startup, which is also safe with several tasks because Flyway locks the database while it migrates. The team may keep startup migrations for local development; `client-profile-management-service` and the auditor still need their SQL files converted into versioned migrations.
