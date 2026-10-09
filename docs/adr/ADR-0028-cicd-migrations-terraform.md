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

- **One migration task per service before deployment (chosen):** the application's database user needs no schema rights, and a failed migration stops the pipeline before new tasks start.
- **Migrations on application startup:** simpler, and safe with several tasks because Flyway locks; but the application user needs schema rights. Allowed for local development.
- **One shared migration job for all databases:** breaks database-per-service. Rejected.

### Infrastructure as code

- **Terraform (chosen):** one reusable module per pattern (service, database), one stack per legal entity ([ADR-0026](ADR-0026-one-stack-per-legal-entity.md)).
- **CloudFormation or CDK:** AWS-only, with less team familiarity.

## Decision

1. **Pipeline (GitHub Actions)** on every pull request and merge:
   - lint and unit tests;
   - Testcontainers tests (Postgres, LocalStack SQS);
   - contract checks (`buf lint`, `buf breaking`, OpenAPI tests);
   - CodeQL and dependency scanning;
   - image build and ECR scan on push;
   - `terraform plan` with checkov;
   - each service's migration task;
   - the ECS deploy and smoke tests.

   Promotion follows `dev → staging → main` (`project_plan.md` §5).
2. **AWS access** from CI only through an OIDC-federated IAM role with least privilege.
3. **db-migrate:** one ECS task definition per service, using Flyway (Java) or golang-migrate (Go), against only that service's database, run before the service deploys. The Go services' SQL files become versioned migrations. Whether staging and prod run migrations as tasks or on startup is still open ([open item 8](../architecture/open-items.md#8-migrations-on-startup-or-as-a-task)).
4. **Terraform** defines all infrastructure. State is kept remotely with locking and encryption (for example an S3 backend). The exact backend is chosen when the infrastructure work starts.

## Rationale

OIDC removes the most common CI credential leak. Separate migration tasks keep schema changes out of the application's privileges and fail fast. Terraform supports the per-entity stacks and the staging create-and-destroy lifecycle that the budget depends on.

## Consequences

### Positive

- No AWS keys in GitHub.
- Broken migrations, contract breaks and vulnerable images are caught before deployment.
- Staging can be rebuilt from code at any time.

### Negative / Trade-offs

- More pipeline steps and longer CI runs.
- A migration task per service to maintain.
- The infrastructure code is still to be written, and remote state needs a bootstrap step.

## Security & Privacy Impact

The OIDC trust policy is restricted to this repository and its protected branches. Application database users have no DDL rights. checkov flags insecure Terraform. No secrets in git or images.

## Cost Impact

GitHub Actions minutes are within the free allowance for this project. Migration tasks run for seconds per deployment.

## Operational Impact

Runbooks are needed for deploying to development, staging and production, and for rollback (`docs/runbooks/`). ECS rolls back failed deployments automatically.

## Validation

A pull request with a breaking `.proto` change, a failing migration or a high-severity image finding is blocked by CI. Staging is created from Terraform, tested and destroyed.

## References

- [Schema migrations](../database/migrations.md)
- [NFR mapping: NFR 10, 11 and 13](../architecture/nfr-mapping.md)
- [Open item 8](../architecture/open-items.md#8-migrations-on-startup-or-as-a-task)
- `project_plan.md` §5 (branching and promotion)
