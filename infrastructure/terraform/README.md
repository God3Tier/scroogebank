# Terraform

Infrastructure for the CRM's target architecture ([system overview](../../docs/architecture/system-overview.md)), decided in [ADR-0028](../../docs/adr/ADR-0028-cicd-migrations-terraform.md) and [ADR-0042](../../docs/adr/ADR-0042-terraform-deployment.md). How to deploy it: [docs/runbooks/deploy-aws.md](../../docs/runbooks/deploy-aws.md).

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
| `running` | `false` between sessions: 0 tasks, schedules paused, no NAT gateway (ADR-0029, ADR-0042 decision 4) |
| `high_availability` | The demo week: Multi-AZ standbys and a NAT gateway per AZ |
| `enable_cognito_triggers` | The login and password-reset audit Lambda (ADR-0040); needs its zip. Keep `true`: `false` is for the mocked tests and leaves sign-ins unaudited |
| `enable_ingest_lambda` | The transaction import Lambda (ADR-0024); off until the receiver has a handler |
| `deletion_protection` | Defaults to on for prod; apply with `false` before `terraform destroy` |
| `enable_github_deploy_role` | The role a deploy workflow assumes; off while deployments are run by hand |

## What each stack deploys

The account must be on the AWS paid plan (ADR-0042 decision 3). Apply the stacks in this order: `bootstrap`, `crm`, `sftp-server`, then `crm` again with the SFTP server's address. Task owners are in the [infrastructure plan](../../docs/feature-plans/infrastructure_plan.md).

### `bootstrap`

What every stack in the account shares:

- **State bucket:** the S3 bucket for Terraform state. It is versioned, encrypted and TLS-only.
- **CloudTrail:** management events, kept for 90 days, and data events on the audit anchor buckets.
- **GuardDuty:** threat detection for the account.
- **Budgets:** `crm-total` measures spending against the USD 300 project limit, and `crm-bedrock` against USD 25 a month. Both count cost before credits.

The GitHub OIDC provider (`enable_github_oidc`) is off until CI deploys.

### `stacks/crm`

The CRM for one legal entity and environment:

| Area | Deployed |
|---|---|
| Network | A VPC across 2 Availability Zones, with public, app and database subnets. One NAT gateway, removed while the stack is stopped; its Elastic IP stays. Gateway endpoints for S3 and DynamoDB |
| Edge and API | CloudFront serves the frontend from a private bucket and forwards `/api/*` to an API Gateway HTTP API. API Gateway checks the Cognito token, then passes requests through a VPC Link to the internal ALB, which routes by path. In prod, WAF sits on CloudFront |
| Sign-in | A Cognito user pool with ADMIN and AGENT groups and managed login. The Cognito triggers Lambda, which audits logins and password resets |
| Services | On ECS Fargate, 2 to 6 tasks each, and 0 while stopped: user-service, client-service, account-service, the auditor, chain-verify (daily at 03:00 SGT), transaction-service, dashboard-service, and event-alert-service (its read API only). Also the ECR repositories, Service Connect, the project CA and the service certificates |
| Data | 6 RDS PostgreSQL instances: users, clients, accounts, transactions, audit and alerts. Each is `db.t4g.micro` with 20 GB of gp3 that can grow to 100 GB, and 7 days of point-in-time recovery. Also the `client-keys` DynamoDB table, the ID-documents bucket, 4 Singapore KMS keys, one Sydney backup key and a psql console task for each database |
| Audit | The SQS queue and its dead-letter queue. The Object Lock bucket for chain-verify's anchors |
| Backups | Prod only: a daily AWS Backup of the databases and `client-keys`, copied to a locked vault encrypted by a customer-managed key in Sydney |
| Monitoring | An SNS alarm topic that sends e-mail. Alarms on the audit queue, audit send failures, ALB 5xx errors, API latency, Cognito trigger errors and chain tampering. A dashboard |

`high_availability = true` adds Multi-AZ standbys and a second NAT gateway. Staging has no WAF and no backups, and uses Fargate Spot (ADR-0029).

### `stacks/sftp-server`

Stands in for the bank's core banking system, Feature 3's external server. It has its own VPC and contains:

- a `t4g.micro` EC2 instance running OpenSSH, with a fixed Elastic IP;
- one SFTP-only account that accepts key sign-in from the CRM's NAT address only, with a pinned host key;
- an ECR repository for the producer container, which writes a synthetic transaction file every 5 minutes once `producer_image` is set.

### Deployed but not working yet

| Part | Why it doesn't work | Waiting for |
|---|---|---|
| transaction-service | It is the SFTP receiver's read API (`services/sftp/receiver/src/serverd`). It has no production image, so it cannot start, and transaction reads return 502 | INF-05: a production Dockerfile for the receiver |
| event-alert-service | It runs, but nothing creates alerts, so it has none to show | INF-06: the alert worker |
| SFTP server | It writes files, but nothing collects them | INF-04: the ingest Lambda |
| Database logins | Most services sign in as their database's master user, and schemas are applied by hand | INF-03: db-migrate tasks |
| Internal ALB | It serves HTTP, not HTTPS, inside the VPC | [Open item 2](../../docs/architecture/open-items.md#2-domain-name): a domain name, so ACM can issue a certificate |
| ALB to the Java services | user-service, dashboard-service and event-alert-service get plain HTTP | INF-08: TLS in those services |
| CloudFront | It uses the default `*.cloudfront.net` address | [Open item 2](../../docs/architecture/open-items.md#2-domain-name): a domain name |

### Not deployed

| Part | Waiting for |
|---|---|
| transaction-ingest Lambda (`enable_ingest_lambda`), the SFTP receiver's poller (`services/sftp/receiver/src/cronjob`) | INF-04: a Lambda handler for the poller that reads its secrets from Secrets Manager. Until then, no transactions reach transactions-db |
| Alert worker, its 4-hourly schedule, AgentCore web search in Tokyo and Bedrock access | INF-06 (EDA-D1-02), blocked until the alerts ADRs are accepted |
| Health monitor, its daily analysis task and health-db | INF-07: the service does not run yet |
| GitHub deploy role (`enable_github_deploy_role`) and CI workflows | INF-09: deferred while deployments are run by hand |
| SES e-mail for client verification | [Open item 19](../../docs/architecture/open-items.md#19-client-verification-request-feature-2): agents check ID documents by hand for now |

## Checks

```sh
cd infrastructure/terraform/stacks/crm
terraform init -backend=false
terraform validate
terraform test      # plans each mode against mocked providers; no AWS account needed
```

`bootstrap/` has its own `terraform test` for the budget alerts.

Real `*.tfvars` and `*.s3.tfbackend` files are git-ignored; copy the `.example` files next to them.
