# Terraform

Infrastructure for the CRM's target architecture ([system overview](../../docs/architecture/system-overview.md)), decided in [ADR-0028](../../docs/adr/ADR-0028-cicd-migrations-terraform.md) and [ADR-0042](../../docs/adr/ADR-0042-terraform-deployment-and-free-plan-mode.md). How to deploy it: [docs/runbooks/deploy-aws.md](../../docs/runbooks/deploy-aws.md).

| Directory | What it holds | State |
|---|---|---|
| `bootstrap/` | Account-wide: the state bucket, CloudTrail, GuardDuty, AWS Budgets, the GitHub OIDC provider (off) | Local, applied once per account |
| `stacks/crm/` | One legal entity's whole stack (ADR-0026): network, edge, API entry, Cognito, ECS services, databases, audit pipeline, alarms, backups | `crm/<entity>/<environment>.tfstate` |
| `stacks/sftp-server/` | The simulated core banking SFTP server, an external system with its own stack (ADR-0024) | `sftp-server/<environment>.tfstate` |
| `modules/postgres/` | One RDS PostgreSQL instance for one service (ADR-0023) | |
| `modules/ecs-service/` | One Fargate service or scheduled task, with its roles, logs, Service Connect, ALB rules, auto scaling and TLS files | |
| `sql/` | One-time SQL the runbook runs | |

## Switches

| Variable | Effect |
|---|---|
| `free_plan` | Stays inside the AWS Free account plan: 1 day of RDS backups, micro instances, no Multi-AZ, no billed extras (ADR-0042 decision 3) |
| `running` | `false` between sessions: 0 tasks, schedules paused, no NAT gateway (ADR-0029, ADR-0042 decision 4) |
| `high_availability` | The demo week: Multi-AZ standbys and a NAT gateway per AZ (paid plan only) |
| `enable_cognito_triggers` | The login and password-reset audit Lambda (ADR-0040); needs its zip. Keep `true`: `false` is for the mocked tests and leaves sign-ins unaudited |
| `enable_ingest_lambda` | The transaction import Lambda (ADR-0024); off until the receiver has a handler |
| `deletion_protection` | Defaults to on for prod; apply with `false` before `terraform destroy` |
| `enable_github_deploy_role` | The role a deploy workflow assumes; off while deployments are run by hand |

## Checks

```sh
cd infrastructure/terraform/stacks/crm
terraform init -backend=false
terraform validate
terraform test      # plans each mode against mocked providers; no AWS account needed
```

`bootstrap/` has its own `terraform test` for the budget alerts.

Real `*.tfvars` and `*.s3.tfbackend` files are git-ignored; copy the `.example` files next to them.
