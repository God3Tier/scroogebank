# ADR-0028: CI/CD with GitHub Actions and OIDC, schema migrations as a task, infrastructure in Terraform

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: [ADR-0046](ADR-0046-human-deploy-access-mfa-role.md) and [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md) in part (decision 1's deploy and smoke-test stages and decision 2's CI deploy role: deployment is run by hand from the runbook as the MFA-protected `crm-infra-admin` role, and every deployment ends with a full teardown; CI keeps the build, test, contract and scan stages; the GitHub OIDC role stays defined but off by default)

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
- **Migrations on application startup:** simpler, and safe with several tasks because Flyway locks; but the application user needs schema rights. Allowed for local development, where user-service and event-alert-service still do it.
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
   - The task of each service with a database has a `db-migrate` init container, which runs before the app container on every task start. The app starts only if it succeeds.
   - It applies that service's migrations to that service's database only. It connects as the database's master user and checks RDS's certificate.
   - Only this container receives the master credentials. Flyway's lock keeps tasks that start together from migrating twice.
   - **The tool is Flyway 11.7.2**, the version user-service uses.
     - One image, `infrastructure/db-migrate/`, holds every service's migrations, one directory per database. Each task applies only its own database's directory.
     - The files stay where the services' builds and tests use them:
       - the Go services' `data/` directories, named `V<n>_<description>.sql`;
       - user-service's and event-alert-service's `src/main/resources/db/migration`, named `V<n>__<description>.sql`, as Spring Boot's Flyway reads them. The stack sets Flyway's separator to `__` for those two databases, so the history matches what their apps already wrote.
   - **A deployed migration is never edited**: the change goes in the next version. Flyway's checksums reject an edited file, and the task does not start.
   - **Locally, the same image migrates each Go service's own Compose database** (`make run`, `make migrate`, the SFTP stack's `db-migrate` service). An edited migration therefore fails on the developer's machine first. Throwaway databases for code generation and tests still load the files with `psql`.
   - **user-service and event-alert-service still run Flyway on startup locally and in their tests.** In AWS the stack turns it off (`SPRING_FLYWAY_ENABLED=false`), and their db-migrate applies the same files. Their code does not change.
   - **Each service connects with its own login, without schema rights.** db-migrate creates the login after every migrate, and sets its password from Secrets Manager. The master user owns the tables, so a login cannot change the schema or create roles.

     | Login | Rights |
     |---|---|
     | `user_service`, `client_service`, `account_service`, `event_alert_service` | Read and change rows in every table except Flyway's history |
     | `transaction_service` | Read `transactions` only, since the service checks no token |
     | `transaction_ingest` (the ingest Lambda) | Read and change rows in every table except Flyway's history |
     | `auditor_app`, `chain_verify` | The roles in [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) decision 2 |

     Granting on every run means a table that a later migration adds is granted with no extra step.
4. **Terraform** defines all infrastructure. State is kept remotely with locking and encryption. [ADR-0042](ADR-0042-terraform-deployment.md) (Proposed) chooses the backend: a versioned, encrypted S3 bucket with native locking.

## Rationale

OIDC removes the most common CI credential leak. A migration container gives each task what a separate migration task would:
- schema changes stay out of the application's privileges;
- a failure stops the deploy.

It needs no step before each deploy, which the current Terraform applies by hand could not guarantee. Terraform supports the per-entity stacks and the staging create-and-destroy lifecycle that the budget depends on.

An application that runs Flyway itself needs schema rights. So in AWS, db-migrate applies user-service's and event-alert-service's migrations too, and every application can use a login without them. Keeping Spring Boot's Flyway for their local runs and tests leaves their code unchanged.

## Consequences

### Positive

- No AWS keys in GitHub.
- Broken migrations, contract breaks and vulnerable images are caught before deployment.
- Staging can be rebuilt from code at any time.
- A bug or injection in an application cannot drop or alter its tables.

### Negative / Trade-offs

- More pipeline steps and longer CI runs.
- One more image, db-migrate, to build and push with the services' images.
- **A Java service's migration reaches AWS only in a new db-migrate image.** The runbook builds every image with one tag, so they go out together.
- **user-service's and event-alert-service's migrations run two ways:** with Spring Boot's Flyway locally, and with db-migrate in AWS. Both use the same files, so a problem shows up locally first.
- **Every task start runs Flyway first.** It pulls a 276 MB image and starts a JVM before the app starts. This is expected to take tens of seconds on Fargate, but hasn't been measured there.
- **Deployed migrations can't be edited.** Until now the Go services' SQL files were edited in place. A deployed file is now final, and changes go in the next version.
- Remote state needs a bootstrap step.

## Security & Privacy Impact

- The OIDC trust policy is restricted to this repository and its protected branches.
- **Who holds schema rights.**
  - Only a task's db-migrate container receives its database's master credentials.
  - Every application, and the ingest Lambda, logs in without DDL rights (decision 3).
  - The database console (`scripts/db-run-sql.sh`) still runs SQL as the master user, for an operator.
- **The login passwords.**
  - Each login has its own Secrets Manager secret. ECS gives an app container only its own login, and a db-migrate container only the passwords it sets. The ingest Lambda reads only its own.
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
- The six login secrets other than the auditor's cost USD 0.40 a month each, USD 2.40 a month per stack, running or stopped.

## Operational Impact

- Runbooks are needed for deploying to development, staging and production, and for rollback (`docs/runbooks/`).
- ECS rolls back failed deployments automatically.
- A new stack needs no database step by hand. The runbook builds and pushes db-migrate with the other images.
- Each task's db-migrate log stream says what Flyway applied, or why it stopped.
- A `permission denied` error in an application's log means a table its login was not granted, such as one created outside a migration. The next task start grants every table that a migration created.
- Each login callback takes an advisory lock first, so tasks that start together don't fail on concurrent grants.

## Validation

- A pull request with a breaking `.proto` change, a failing migration or a high-severity image finding is blocked by CI.
- Staging is created from Terraform, tested and destroyed.
- `terraform test` in `modules/ecs-service` checks:
  - db-migrate runs between tls-init and the app;
  - only it receives its credentials and its other Flyway settings;
  - it requires the TLS files.
- `stacks/crm`'s tests check:
  - every service with a database migrates it;
  - each database's db-migrate gets only the passwords of the logins it creates;
  - each service and the ingest Lambda read their own login's secret, never the master secret;
  - user-service and event-alert-service set the separator `__` and turn their own Flyway off.
- **The image was run locally against PostgreSQL 17.** Each database migrated from empty, and a rerun changed nothing. The audit logins worked and held their roles, and a new password replaced the old one. An edited migration was rejected. `verify-full` worked against a bundle of two CAs, and rejected a wrong CA and a wrong host name.
- **Each Go service's local migration was run.** `make migrate` (account-service, client-service, the auditor) and `make db-up` (the SFTP stack) migrated each Compose database, and a rerun changed nothing. On account-service, a database loaded with `psql` and an edited migration were both refused.
- **In AWS (2026-10-20),** db-migrate migrated client-service's, account-service's, transaction-service's and the auditor's databases, and the services started.
- **The logins were checked locally against PostgreSQL 17 (2026-10-20),** from another container, so passwords were checked:
  - Each login signed in with its placeholder password. After a rerun with a new password, the old one was refused.
  - Each could read and change rows. None could create, alter or drop a table, read Flyway's history or create a role.
  - `transaction_service` could only read `transactions`. `transaction_ingest` ran the receiver's upsert and its outbox relay.
  - A table created after a migrate was granted by the next one.
  - Three migrations of one empty database at once all succeeded, and the advisory lock made the callbacks wait for each other.
  - A database first migrated by Flyway with the `__` separator, as Spring Boot's does, was "up to date" for db-migrate with `__`. Without the setting, Flyway reported "Migration description mismatch".
- **Still to check in AWS:** every service and the ingest Lambda run under their own logins, with no `permission denied` or `password authentication failed` in their logs.

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
- 2026-10-20: amended again while Proposed, as decided by the decision owner (INF-03, application logins). Decision 3 changes:
  - in AWS, db-migrate also applies user-service's and event-alert-service's migrations, and their own Flyway is off there;
  - every service, and the ingest Lambda, connects with its own login without schema rights;
  - transaction-service's login only reads.

  Options Considered, Rationale, Consequences, Security & Privacy Impact, Cost Impact (USD 2.40 a month per stack for the secrets), Operational Impact and Validation changed to match.
