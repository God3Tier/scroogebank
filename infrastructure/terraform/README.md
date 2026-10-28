# Terraform

Infrastructure for the CRM's target architecture ([system overview](../../docs/architecture/system-overview.md)), decided in [ADR-0028](../../docs/adr/ADR-0028-cicd-migrations-terraform.md) and [ADR-0042](../../docs/adr/ADR-0042-terraform-deployment.md). How to deploy it: [docs/runbooks/deploy-aws.md](../../docs/runbooks/deploy-aws.md).

| Directory | What it holds | State |
|---|---|---|
| `bootstrap/` | Account-wide: the state bucket, CloudTrail, AWS Budgets, the GitHub OIDC provider (off) | Local, applied once per account |
| `stacks/crm/` | One legal entity's whole stack (ADR-0026): network, edge, API entry, Cognito, ECS services, databases, audit pipeline, alarms, backups | `crm/<entity>/<environment>.tfstate` |
| `stacks/business-dashboard/` | dashboard-service (X-factor 2), inside one CRM stack's network and cluster; reads that stack's `dashboard_inputs` output (ADR-0047) | `business-dashboard/<entity>/<environment>.tfstate` |
| `stacks/alert-worker/` | The event-alert worker (X-factor 1): a scheduled ECS task every four hours, its IAM and a failed-run alarm, inside one CRM stack's cluster; reads that stack's `alerts_worker_inputs` output (ADR-0048) | `alert-worker/<entity>/<environment>.tfstate` |
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
| `enable_ingest_lambda` | The transaction import Lambda (ADR-0024); needs its zip and the sftp-server stack's address and host key ([runbook §9.3](../../docs/runbooks/deploy-aws.md#93-turn-on-the-ingest-lambda)) |
| `domain_name` | A domain registered in Route 53 ([runbook](../../docs/runbooks/deploy-aws.md#register-a-domain-once-per-account)): the app at `sg.<domain>` and the internal ALB on HTTPS (ADR-0042 decision 8). Unset, the app keeps its `*.cloudfront.net` address and the ALB listens on HTTP inside the VPC. The project account's domain is `itsag3t6.com`; runbook step 3 sets it only in an account that has that domain's hosted zone, so other accounts deploy without one |
| `deletion_protection` | Defaults to on for prod; apply with `false` before `terraform destroy` |
| `enable_github_deploy_role` | The role a deploy workflow assumes; off while deployments are run by hand |

## What each stack deploys

The account must be on the AWS paid plan (ADR-0042 decision 3). Apply the stacks in this order: `bootstrap`, `crm`, `business-dashboard`, `alert-worker`, `sftp-server`, then `crm` again with the SFTP server's address. Destroy `business-dashboard` and `alert-worker` before `crm`. Task owners are in the [infrastructure plan](../../docs/feature-plans/infrastructure_plan.md).

### `bootstrap`

What every stack in the account shares:

- **State bucket:** the S3 bucket for Terraform state. It is versioned, encrypted and TLS-only.
- **CloudTrail:** management events, kept for 90 days, and data events on the audit anchor buckets.
- **Budgets:** `crm-total` measures spending against the USD 300 project limit, and `crm-bedrock` against USD 25 a month. Both count cost before credits.

The GitHub OIDC provider (`enable_github_oidc`) is off until CI deploys. GuardDuty is not created here: the account already has a detector ([Left out of the account](#left-out-of-the-account)).

Two account-wide things are made by hand, outside Terraform, so no destroy removes them: the deploy role `crm-infra-admin` (ADR-0046), and the domain with the Route 53 hosted zone that registering it creates ([runbook](../../docs/runbooks/deploy-aws.md#register-a-domain-once-per-account)).

### `stacks/crm`

The CRM for one legal entity and environment:

| Area | Deployed |
|---|---|
| Network | A VPC across 2 Availability Zones, with public, app and database subnets. One NAT gateway, removed while the stack is stopped; its Elastic IP stays. Gateway endpoints for S3 and DynamoDB |
| Edge and API | CloudFront serves the frontend from a private bucket and forwards `/api/*` to an API Gateway HTTP API. API Gateway checks the Cognito token, then passes requests through a VPC Link to the internal ALB, which routes by path and reaches every service over HTTPS. In prod, WAF sits on CloudFront, without request logging. With `domain_name`: the app at `sg.<domain>` (`sg-staging.<domain>` for staging), ACM certificates for it (in us-east-1) and for `alb.sg.<domain>`, and HTTPS from API Gateway and the ingest Lambda to the ALB, with API Gateway checking the certificate's name |
| Sign-in | A Cognito user pool with ADMIN and AGENT groups and managed login. The Cognito triggers Lambda, which audits logins and password resets |
| Services | On ECS Fargate, 1 to 6 tasks each, and 0 while stopped: user-service, client-service, account-service, the auditor, chain-verify (daily at 03:00 SGT), transaction-service, and event-alert-service (its read API only). Also their ECR repositories, Service Connect, the project CA and the service certificates. dashboard-service runs in the same cluster but is deployed by `stacks/business-dashboard` |
| Data | 6 RDS PostgreSQL instances: users, clients, accounts, transactions, audit and alerts. Each is `db.t4g.micro` with 20 GB of gp3 that can grow to 100 GB, and 7 days of point-in-time recovery. Also the `client-keys` DynamoDB table, the ID-documents bucket, 4 Singapore KMS keys and a psql console task for each database |
| Audit | The SQS queue and its dead-letter queue. The Object Lock bucket for chain-verify's anchors |
| Backups | Prod only: a daily AWS Backup of the databases and `client-keys`, kept for 7 days in a Singapore vault. There is no copy in another Region |
| Monitoring | An SNS alarm topic that sends e-mail. Alarms on the audit queue, audit send failures, ALB 5xx errors, API latency, Cognito trigger errors and chain tampering. A dashboard |

`high_availability = true` adds Multi-AZ standbys and a second NAT gateway. Staging has no WAF and no backups, and uses Fargate Spot (ADR-0029).

### `stacks/business-dashboard`

dashboard-service, the business dashboard (ADR-0033), for one CRM stack (ADR-0047). It reads the CRM stack's `dashboard_inputs` output from that stack's state, so apply the CRM stack first. It contains:

- the dashboard-service ECR repository;
- the service on Fargate (1 vCPU, 2 GB), behind the internal ALB at `/api/dashboard/*` over HTTPS, as a Service Connect client only;
- its TLS certificate, self-signed because only the ALB calls it and the project CA's key stays in the CRM stack, in a Secrets Manager secret;
- its security group, and rules on the CRM's user, client and account groups that let it call those services;
- a policy on the CRM's GitHub deploy role, when that role exists.

Its tasks follow the CRM stack's `running`: apply this stack again after every CRM apply that changes it. The audit queue must allow its task role: put the ARN in the CRM stack's `extra_audit_producer_role_arns`. The plan fails until you do.

### `stacks/alert-worker`

The event-alert worker (X-factor 1, [ADR-0048](../../docs/adr/ADR-0048-alert-worker-terraform-stack.md)), inside the CRM stack's cluster, subnets and alerts security group:

| Area | Deployed |
|---|---|
| Task | A scheduled ECS Fargate task (0.5 vCPU, 1 GB) from the event-alert-service image and tag the CRM stack deploys, started with `ALERT_MODE=WORKER`. No service, repository or database of its own |
| Schedule | An EventBridge Scheduler schedule, every four hours in Singapore time, with a role that can only run that task. `ENABLED` only while the CRM stack's `running` is true **and** `ingestion_enabled` is true |
| Alarm | An EventBridge rule: a non-zero exit publishes to the CRM stack's alarm topic |
| IAM | Bedrock on the pinned inference profile only with `ai_enabled`; AgentCore `InvokeGateway` only with `ingestion_enabled` and `agentcore_gateway_arn` |

`ingestion_enabled`, `ai_enabled` and `cloudwatch_metrics_enabled` default to false. The Tokyo AgentCore gateway is not created here: its resources wait for the live spike, and `agentcore_gateway_url` and `agentcore_gateway_arn` point the worker at one made by hand. The `replay_command` output starts a one-off replay task (ADR-0049). After a CRM apply that changes `running`, apply this stack again.

### `stacks/sftp-server`

Stands in for the bank's core banking system, Feature 3's external server. It has its own VPC and contains:

- a `t4g.micro` EC2 instance running OpenSSH, with a fixed Elastic IP;
- one SFTP-only account that accepts key sign-in from the CRM's NAT address only, with a pinned host key. Terraform generates the host key, or takes one you were given from `host_key_file` ([runbook §9.1](../../docs/runbooks/deploy-aws.md#91-create-the-sftp-server));
- an ECR repository for the producer container, which writes a synthetic transaction file every 5 minutes once `producer_image` is set.

### Deployed but not working yet

| Part | Why it doesn't work | Waiting for |
|---|---|---|
| event-alert-service | It runs, but nothing creates alerts, so it has none to show | INF-06: the alert worker |
| Database logins | Most services sign in as their database's master user, and schemas are applied by hand | INF-03: db-migrate tasks |

### Left out of the account

The account the stack is deployed to refuses or already has these, so the code leaves them out. The commented-out blocks stay in the files, to restore when the account allows them.

| Part | Where | Why it is left out | Effect |
|---|---|---|---|
| GuardDuty detector | `bootstrap/main.tf` (deleted) | The account already has a detector, and GuardDuty allows only one per account per Region, so creating another fails | None: the existing detector does the threat detection ADR-0027 asks for. It is not in Terraform, so `terraform destroy` does not remove it |
| Cross-Region backup copy: the Sydney KMS key and its alias, the Sydney vault, its Vault Lock and the backup plan's `copy_action` | `stacks/crm/backup.tf` (commented out) | The account's service control policy denies `backup:CreateBackupVault` in the DR Region, ap-southeast-2 | Backups exist only in Singapore. If ap-southeast-1 is unavailable, they cannot be restored elsewhere, so ADR-0023's cross-Region recovery is not met. No Vault Lock protects the Singapore recovery points |
| WAF request logging: the `aws-waf-logs-*` log group and the web ACL's logging configuration | `stacks/crm/edge.tf` (commented out) | The service control policy denies `logs:CreateLogGroup` in us-east-1, where CloudFront's WAF and its log group must be | The web ACL still blocks requests, but nothing records which ones, or why. WAF's CloudWatch metrics and sampled requests remain |

To restore one, uncomment it, and for the DR vault the prod assertion in `stacks/crm/tests/plan_modes.tftest.hcl`; for WAF logging, put `aws_wafv2_web_acl_logging_configuration` back in that test's WAF assertion.

### Not deployed

| Part | Waiting for |
|---|---|
| Alert worker, its 4-hourly schedule, Tavily secret/internet egress and Bedrock Runtime access from Singapore | INF-06 (EDA-D1-02), implemented behind disabled-by-default switches; live benchmark and reviewed apply remain |
| Health monitor, its daily analysis task and health-db | INF-07: the service does not run yet |
| GitHub deploy role (`enable_github_deploy_role`) and deploy workflows. `.github/workflows/ci.yml` builds and tests every pull request, but nothing pushes images or applies Terraform | INF-09: deferred while deployments are run by hand |
| SES e-mail for client verification | [Open item 19](../../docs/architecture/open-items.md#19-client-verification-request-feature-2): agents check ID documents by hand for now |

## Checks

```sh
cd infrastructure/terraform/stacks/crm
terraform init -backend=false
terraform validate
terraform test      # plans each mode against mocked providers; no AWS account needed
```

`stacks/business-dashboard/` runs the same way; its test stands in for the CRM stack's state, and checks the rules on the CRM's groups and that the plan fails while the audit queue does not allow its role. `modules/ecs-service/` has its own for how a Java service serves TLS, `bootstrap/` one for the budget alerts, and `stacks/sftp-server/` one for `host_key_file`. The sftp-server test writes throwaway keys under its `.terraform/` folder and deletes them when it finishes.

Real `*.tfvars` and `*.s3.tfbackend` files are git-ignored; copy the `.example` files next to them.
