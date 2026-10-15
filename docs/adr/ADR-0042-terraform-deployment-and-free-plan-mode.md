# ADR-0042: Terraform deployment of the target architecture, with a Free-plan mode

- Status: Proposed
- Decision date/time: 2026-10-14 21:38 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/terraform`
- Related issue/PR: TBD
- Supersedes: on acceptance, [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md) decision 5 in part (the `appProtocol: grpc` on the auditor's port; decision 7 below)
- Superseded by: N/A

## Context

[ADR-0028](ADR-0028-cicd-migrations-terraform.md) (Proposed) puts all infrastructure in Terraform, but no infrastructure code existed. The team now needs to deploy the target architecture by hand from a developer machine, to test it, before any CI pipeline runs Terraform.

Some team members' AWS accounts are on the **Free account plan** (accounts opened after 15 July 2025 can choose it). That plan:

- refuses some settings outright with `FreeTierRestrictionError`: RDS instance classes above micro, and automated backup retention above **1 day**, which is also the point-in-time recovery window;
- offers no short-term trials, so GuardDuty's 30-day trial is not free there;
- ends after six months or when its USD 100–200 of credits run out, whichever comes first, and then the account closes.

AWS documents the plan's terms, but not these per-service limits; they come from the error messages reported by teams applying Terraform to new accounts. Verify them against the account before relying on them.

Building the stack also showed that the code on `dev` needs more from the deployment than the architecture documents describe:

- the Go services refuse to start in production mode without TLS certificate files, `DB_SSLMODE=verify-full` with a CA file, and `https://` URLs for the services they call;
- the auditor and chain-verify must not log in as the database owner ([ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) decision 2), but nothing creates their logins yet;
- some components are not deployable yet.

## Decision Drivers

- Deployable today, by hand, from a developer machine; CI comes later
- Fits Free plan accounts as well as the USD 300 project limit
- Follows the accepted ADRs, and records every deviation
- Least privilege; no secret in git, images or task definitions
- Every team member can explain it

## Options Considered

### Free-plan handling

- **One boolean, `free_plan` (chosen).** Every setting the plan refuses or that spends credits for no functional gain follows it, so an apply on a Free plan account cannot miss one.
- **A separate variable for each setting.** More flexible, but easy to forget one, such as backup retention, and the apply then fails part-way.
- **Require a paid plan.** No extra code, but loses the Free plan's guarantee of no charges, and not every member can upgrade.

### Getting certificates and CA trust into containers

- **An init container writes the files from Secrets Manager into a task volume (chosen).** No code change; nothing secret in images or task definitions.
- **Bake them into the images.** Puts secrets in images (project_plan.md section 3, rule 8).
- **AWS Private CA with Service Connect TLS.** Managed, but at least about USD 50 a month for a short-lived CA, against the USD 300 limit; ADR-0021 decision 7 already rejected it.
- **Change each service to read PEM text from environment variables.** Simpler tasks, but code changes in four services before anything can be deployed.

### Terraform state

- **S3 with native locking (`use_lockfile`, Terraform 1.10 and later) (chosen).** One versioned, encrypted bucket; no lock table.
- **S3 with a DynamoDB lock table.** Deprecated by Terraform in favour of native locking.
- **HCP Terraform.** Hosted state and runs, but another service for the team to sign up to and grant access to.

## Decision

1. **Layout.** Everything is under `infrastructure/terraform/`:
   - `bootstrap/` holds account-wide resources, applied once per account with local state: the state bucket, CloudTrail, GuardDuty, AWS Budgets and, behind a flag, the GitHub OIDC provider.
   - `modules/postgres` and `modules/ecs-service` are one module per pattern (ADR-0028).
   - `stacks/crm` is one stack per legal entity and environment ([ADR-0026](ADR-0026-one-stack-per-legal-entity.md)), with state at `crm/<entity>/<environment>.tfstate`.
   - `stacks/sftp-server` is the external bank's SFTP server, in its own stack ([ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 1).
2. **State.** A versioned, KMS-encrypted, TLS-only S3 bucket with native locking. This settles ADR-0028 decision 4, which left the backend to be chosen when the infrastructure work started.
3. **`free_plan`.** One variable in each stack:

   | Setting | Paid plan | Free plan | Why |
   |---|---|---|---|
   | RDS instance class | `db.t4g.micro` | `db.t4g.micro` | The plan allows micro classes only |
   | RDS backup retention (PITR window) | 7 days | **1 day** | The plan's maximum |
   | RDS Multi-AZ | in the demo week | never | Doubles database cost |
   | RDS storage auto scaling | up to 100 GiB | off (20 GiB) | Stays at the free-tier size |   | DynamoDB `client-keys` | on-demand, PITR on | provisioned 5/5, PITR off | Inside the always-free 25 RCU/WCU; PITR is billed per GB |
   | AWS Backup with a cross-region copy | prod | off | Billed storage and transfer; RDS PITR still applies |
   | WAF | prod | off | About USD 10 a month |
   | GuardDuty, CloudTrail data events | on | off | GuardDuty's trial is a paid-plan offer |
   | Tasks per service | 2–6 | 1–2 | Half the Fargate cost |
   | Log retention | 30 days | 7 days | Storage |
   | SFTP server instance | `t4g.micro` | `t4g.micro` (forced) | Free-tier eligible |

   None of these changes how the services behave. The tests in `stacks/crm/tests/` plan each mode against mocked providers and check these values.
4. **Stop model.** `running = false` scales every service to 0 tasks, pauses the schedules and **removes the NAT gateways**. Their Elastic IPs are separate resources and stay allocated, so a recreated NAT gateway gets the same address and the SFTP server's allow-list never changes. This refines ADR-0029, which kept the NAT gateway up for that reason. `scripts/aws-session.sh` stops and starts the RDS instances and the SFTP server's instance. `high_availability = true` is the demo week: Multi-AZ standbys and a NAT gateway per AZ ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)).
5. **Certificates and trust.** Terraform's `tls` provider creates the project CA (ADR-0021 decision 7) and a certificate for each service that serves TLS: client-service, account-service and the auditor, named for their Service Connect aliases. The certificates and keys are kept in Secrets Manager. In each task, a `tls-init` container writes `/tls`:
   - `ca.pem`: the project CA;
   - `rds-ca.pem`: the RDS CA bundle for the region;
   - `ca-bundle.pem`: public roots plus both CAs, which Go reads through `SSL_CERT_FILE`;
   - `cert.pem` and `key.pem`: the service's own certificate;
   - for Java services, a JVM truststore that adds the project CA.

   The private keys are also in the Terraform state, so only administrators may read the state bucket.
6. **Database logins.** The auditor and chain-verify log in as `auditor_app` and `chain_verify`, holding the roles `auditor_service` and `auditor_chain_verify` (ADR-0022 decision 2). Terraform generates their passwords; the logins are created once by hand (runbook) until the db-migrate task exists. Every other service connects as its instance's RDS-managed master user until then. This is a temporary deviation from ADR-0028 decision 3 (no schema rights for application users, [open item 8](../architecture/open-items.md#8-migrations-on-startup-or-as-a-task)).
7. **Service Connect proxies TLS ports as TCP.** The auditor's gRPC and HTTPS ports, client-service and account-service have no `appProtocol`. Their TLS is end to end (ADR-0021 decision 7), so Envoy cannot read gRPC or HTTP inside it, and `appProtocol: grpc` (ADR-0021 decision 5) would break the connection. Envoy balances connections, and the server's `MaxConnectionAge` makes callers reconnect, as decision 7 already says. Plain-HTTP services (user-service, transaction-service) keep `appProtocol: http`.
8. **Internal ALB.**
   - It listens on HTTP inside the VPC until there is a domain for an ACM certificate ([open item 2](../architecture/open-items.md#2-domain-name)); setting `internal_alb_certificate_arn` switches it to HTTPS, with API Gateway verifying the name.
   - Target groups use HTTPS for the Go services, which serve TLS in production, and HTTP for the Java services, which do not. The security model's "self-signed TLS between the ALB and tasks" is therefore not met for the Java services yet.
9. **Not deployed yet, each a task in `docs/feature-plans/infrastructure_plan.md`:**
   - the transaction-ingest Lambda (`enable_ingest_lambda`, off): the receiver has no Lambda handler, and reads its secrets from environment variables and files rather than from Secrets Manager at start;
   - the image for transaction-service: the receiver's Dockerfile is a development image;
   - the health monitor: it is not runnable yet;
   - the alert worker, its schedule, AgentCore in Tokyo and Bedrock access: EDA-D1-02 (Azeez);
   - the GitHub deploy role (`enable_github_deploy_role`, off) and any CI checks on Terraform: deployments are run by hand for now.
10. **Smaller choices:**
    - the audit queues use SQS-managed encryption rather than a customer-managed key, so producers need no KMS permissions;
    - the audit queue's policy refuses `SendMessage` from every role except the producers and the replay role;
    - the anchor bucket refuses writes from everyone except chain-verify;
    - transaction-service, which checks no token, accepts connections from user-service only.
    - the budget alerts count cost before credits, which AWS Budgets subtracts by default, so they fire even when credits cover the bill. `crm-total` is one yearly budget from the project's start date, because ADR-0029's USD 300 is a limit for the whole project, not for each month.

## Rationale

One switch matches how the team decides: an account is either on the Free plan or not. Deriving every affected setting from it means a Free-plan apply cannot fail half-way on a forgotten limit. The init container gets the current code running without code changes or secrets in images, and leaves room to remove it once services read PEM text directly. Native S3 locking needs nothing besides the bucket. Removing the NAT gateways between sessions saves most of the idle cost, without moving the address the external server trusts.

## Consequences

### Positive

- A Free plan account can apply the whole stack without `FreeTierRestrictionError`.
- Each toggle's effect is tested without an AWS account.
- Deployments are repeatable, and teardown is `terraform destroy`.

### Negative / Trade-offs

- On the Free plan, point-in-time recovery reaches back 1 day with no cross-region copy, client-keys has no point-in-time recovery, and each service runs one task, so NFR 2 and NFR 9 are not met in that mode. Use it for development and testing, not the graded demo of availability.
- Until db-migrate exists, schemas are applied by hand, and most services use their database's master user.
- Starting a session takes longer: the NAT gateway is recreated (about 2–3 minutes) and the RDS instances start (several minutes).
- The CA and service private keys are in the Terraform state.
- ADR-0021 decision 5 and the architecture pages that repeat it (data-flow "Audit read decisions", system-overview "Parts of the architecture not shown on the diagram") need updating when this ADR is accepted.

## Security & Privacy Impact

- No secret is in git, images or task definitions: tasks read Secrets Manager through per-service execution roles.
- The state bucket holds private keys, so access to it must stay restricted to administrators.
- Every task role and the queue, anchor and documents bucket policies follow least privilege.
- The internal ALB's plain HTTP inside the VPC is reachable only from the VPC Link and the ingest Lambda.

## Cost Impact

Rough ap-southeast-1 figures in USD a month for `free_plan = true`; verify them with the AWS Pricing Calculator:

| | Running 24/7 | Stopped (`running = false`, RDS stopped) |
|---|---|---|
| NAT gateway | ~43 | 0 (Elastic IP ~4) |
| Internal ALB | ~20 | ~20 |
| 6 × RDS `db.t4g.micro`, single-AZ | ~125 | ~17 (storage) |
| Fargate, 7 services × 1 task | ~110 | 0 |
| KMS, Secrets Manager, CloudWatch, API Gateway, CloudFront, other | ~15 | ~10 |
| **Total** | **~315** | **~50** |

The Free plan's USD 100–200 of credits cover about two to three weeks at 24/7, or one to two months of sessions (two 10-hour sessions a week, about USD 80 a month) with the stack stopped in between. So the stop model is required on the Free plan, not optional.

## Operational Impact

`docs/runbooks/deploy-aws.md` covers:

- bootstrap;
- building and pushing images and the Lambda zip;
- the apply order (crm, then sftp-server, then crm again);
- the one-time database steps and the frontend upload;
- starting and stopping sessions;
- teardown.

`scripts/aws-session.sh` stops and starts the RDS instances and the SFTP server.

## Validation

- `terraform validate` on all three stacks.
- `terraform test` in `stacks/crm`: six runs plan the stack against mocked providers and check the Free plan's limits, the paid plan, the high-availability week, the stopped state, staging, and the Lambda settings from ADR-0024 and ADR-0040.
- The first real apply on a Free plan account must finish without `FreeTierRestrictionError`, and the services must pass their ALB health checks.

## References

- AWS: Choosing a plan (Free account plan terms), AWS Billing and Cost Management User Guide
- Reported Free plan limits: `FreeTierRestrictionError` on RDS backup retention above 1 day and on non-micro instance classes (GitHub issues yc-software/qm#352, aaronpotter/terraform-aws#18)
- [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md), [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md), [ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md), [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md), [ADR-0026](ADR-0026-one-stack-per-legal-entity.md), [ADR-0028](ADR-0028-cicd-migrations-terraform.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0040](ADR-0040-cognito-triggers-java-lambda.md), [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md)
