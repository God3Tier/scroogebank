# ADR-0028: CI/CD with GitHub Actions and OIDC, schema migrations as a task, infrastructure in Terraform

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

NFR 10 asks the team to demonstrate CI/CD; NFR 11 asks for infrastructure defined as code; NFR 13 asks for automated testing. Each service owns its own database ([ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md)) and its schema. Today user-service runs Flyway on startup, while `client-profile-management-service` and the auditor apply plain SQL files. The project's infrastructure code is not built yet; an older Terraform branch will not be used.

## Decision Drivers

- No long-lived AWS keys in CI (NFR 8)
- A bad change must stop before it reaches running tasks
- Least privilege for application database users
- Repeatable environments (staging created and destroyed)

## Options Considered

### Deployment credentials

- **GitHub Actions with an IAM role assumed through OIDC (chosen):** no stored keys.
- **IAM user access keys in GitHub secrets:** long-lived keys that can leak. Rejected.

### Schema migrations

- **An init container in each service's task (chosen):** the task runs its database's migrations before the app starts, with credentials that only the migration container receives. A failed migration starts no new task, and the deployment circuit breaker rolls back. Nothing has to run before a deploy.
  - Cons: Flyway runs at every task start.
  - The task's execution role can read the master secret.
- **One migration task per service before deployment:** gives the same separation of rights, and stops the pipeline before new tasks start.
  - Cons: something must start it before every deploy. There is no pipeline yet, and Terraform applies by hand would have to run it each time.
- **Migrations on application startup:** simpler, and safe with several tasks because Flyway locks; but the application user needs schema rights. Allowed for local development, and what user-service and event-alert-service do today.
- **One shared migration job for all databases:** breaks database-per-service. Rejected.

### Migration tool for the Go services

- **Flyway's command-line image (chosen):** runs the Go services' plain SQL files as they are, with the same tool and version as the Java services.
- **golang-migrate:** needs a code change or a separate image per service, and renamed `up`/`down` files.

### Infrastructure as code

- **Terraform (chosen):** one reusable module per pattern (service, database), one stack per legal entity ([ADR-0026](ADR-0026-one-stack-per-legal-entity.md)).
  - One configuration spans every region the design uses, through provider aliases: ap-southeast-1 for the stack, us-east-1 for CloudFront's WAF, ap-southeast-2 for the backup copies and Tokyo for x-factor 1's web search.
  - The `tls` provider generates the project CA ([ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md) decision 7) without extra code.
  - `terraform destroy` removes staging, including buckets that still hold objects.
  - Cons: the team must keep its state remote, locked and protected, and a failed apply leaves partial changes to fix forward.
- **CloudFormation or CDK:** AWS-only, with the state kept by AWS and a failed update rolled back automatically.
  - Cons: a stack belongs to one region and its exports cannot be read from another, so the multi-region parts need StackSets or workarounds.
  - Generating the CA needs a custom-resource Lambda.
  - Deleting a bucket that still holds objects fails, which slows staging teardown.
  - The team is less familiar with it.

## Decision

1. **Pipeline (GitHub Actions)** on every pull request and merge:
   - lint and unit tests;
   - Testcontainers tests (Postgres, LocalStack SQS);
   - contract checks (`buf lint`, `buf breaking`, OpenAPI tests);
   - CodeQL and dependency scanning;
   - image build and ECR scan on push;
   - `terraform plan` with checkov;
   - the ECS deploy, whose new tasks migrate their databases before starting (decision 3), and smoke tests.

   Promotion follows `dev → staging → main` (`project_plan.md` §5).
2. **AWS access** from CI only through an OIDC-federated IAM role with least privilege.
3. **db-migrate** (resolves [open item 8](../architecture/open-items.md#8-migrations-on-startup-or-as-a-task)):
   - Each Go service's task has a `db-migrate` init container, which runs before the app container on every task start. The app starts only if it succeeds.
   - It applies that service's migrations to that service's database only. It connects as the database's master user and checks RDS's certificate.
   - Only this container receives the master credentials. Flyway's lock keeps tasks that start together from migrating twice.
   - **The tool is Flyway 11.7.2**, the version user-service uses.
     - One image, `infrastructure/db-migrate/`, holds each Go service's `data/` directory as its database's migrations. Each task applies only its own database's directory.
     - The files stay where the services' Makefiles and tests use them, named `V<n>_<description>.sql`.
   - **A deployed migration is never edited**: the change goes in the next version. Flyway's checksums reject an edited file, and the task does not start.
   - **Locally, the same image migrates each Go service's own Compose database** (`make run`, `make migrate`, the SFTP stack's `db-migrate` service). An edited migration therefore fails on the developer's machine first. Throwaway databases for code generation and tests still load the files with `psql`.
   - **audit-db's migration also creates the auditor's and chain-verify's logins** ([ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) decision 2). It sets their passwords from Secrets Manager on every run.
   - **user-service and event-alert-service** keep running Flyway on startup.
   - **Application logins without schema rights come later (INF-03).** Until then, the Go services' apps still connect as the master user ([ADR-0042](ADR-0042-terraform-deployment.md) decision 6).
4. **Terraform** defines all infrastructure. State is kept remotely with locking and encryption. [ADR-0042](ADR-0042-terraform-deployment.md) (Proposed) chooses the backend: a versioned, encrypted S3 bucket with native locking.

## Rationale

OIDC removes the most common CI credential leak. A migration container gives each task what a separate migration task would:
- schema changes stay out of the application's privileges;
- a failure stops the deploy.

It needs no step before each deploy, which the current Terraform applies by hand could not guarantee. Terraform supports the per-entity stacks and the staging create-and-destroy lifecycle that the budget depends on.

## Consequences

### Positive

- No AWS keys in GitHub.
- Broken migrations, contract breaks and vulnerable images are caught before deployment.
- Staging can be rebuilt from code at any time.

### Negative / Trade-offs

- More pipeline steps and longer CI runs.
- One more image, db-migrate, to build and push with the services' images.
- **Every task start runs Flyway first.** It pulls a 276 MB image and starts a JVM before the app starts. This is expected to take tens of seconds on Fargate, but hasn't been measured there.
- **Deployed migrations can't be edited.** Until now the Go services' SQL files were edited in place. A deployed file is now final, and changes go in the next version.
- Remote state needs a bootstrap step.

## Security & Privacy Impact

- The OIDC trust policy is restricted to this repository and its protected branches.
- **Who holds schema rights.**
  - Only a task's db-migrate container receives its database's master credentials.
  - The auditor and chain-verify log in without DDL rights.
  - The other applications still use the master user until INF-03.
- **The login passwords.**
  - They are Flyway placeholders filled from Secrets Manager, so they are in neither git nor the image.
  - Flyway prints a failing statement in full, so a failed `ALTER ROLE … PASSWORD` would log that password. The block before it makes sure the role exists, so the statement has no expected way to fail.
- **TLS to RDS:** db-migrate checks RDS's certificate and name (`sslmode=verify-full`).
- checkov flags insecure Terraform.
- No secrets in git or images.

## Cost Impact

- GitHub Actions minutes are within the free allowance for this project.
- The db-migrate image in ECR costs about USD 0.03 a month for each copy kept. The repository keeps the 10 newest.
- Pulls go through the S3 gateway endpoint at no charge.
- The container shares its task's CPU and memory, and runs before the app needs them.

## Operational Impact

- Runbooks are needed for deploying to development, staging and production, and for rollback (`docs/runbooks/`).
- ECS rolls back failed deployments automatically.
- A new stack needs no database step by hand. The runbook builds and pushes db-migrate with the other images.
- Each task's db-migrate log stream says what Flyway applied, or why it stopped.

## Validation

- A pull request with a breaking `.proto` change, a failing migration or a high-severity image finding is blocked by CI.
- Staging is created from Terraform, tested and destroyed.
- `terraform test` in `modules/ecs-service` checks:
  - db-migrate runs between tls-init and the app;
  - only it receives its credentials;
  - it requires the TLS files.
- `stacks/crm`'s tests check which services migrate, and that only audit-db's migration gets the login passwords.
- **The image was run locally against PostgreSQL 17.** Each database migrated from empty, and a rerun changed nothing. The audit logins worked and held their roles, and a new password replaced the old one. An edited migration was rejected. `verify-full` worked against a bundle of two CAs, and rejected a wrong CA and a wrong host name.
- **Each Go service's local migration was run.** `make migrate` (account-service, client-service, the auditor) and `make db-up` (the SFTP stack) migrated each Compose database, and a rerun changed nothing. On account-service, a database loaded with `psql` and an edited migration were both refused.

## References

- [Schema migrations](../database/migrations.md)
- [NFR mapping: NFR 10, 11 and 13](../architecture/nfr-mapping.md)
- [Open item 8](../architecture/open-items.md#8-migrations-on-startup-or-as-a-task)
- `project_plan.md` §5 (branching and promotion)

## Revision history

- 2026-10-01 21:50 +08:00: proposed.
- 2026-10-20: amended while Proposed, as decided by the decision owner (INF-03), which resolves open item 8. Decision 3 changes:
  - migrations run in an init container in each Go service's task, not a separate task;
  - Flyway is used for the Go services too;
  - audit-db's migration creates the auditor's and chain-verify's logins;
  - the same image migrates each Go service's local database.

  Decision 1's pipeline steps, Options Considered, Rationale, Consequences, Security & Privacy Impact, Cost Impact, Operational Impact and Validation changed to match.
