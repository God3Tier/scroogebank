# ADR-0042: Terraform deployment of the target architecture

- Status: Proposed
- Decision date/time: 2026-10-14 21:38 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/terraform`; decisions 9 and 10 amended on `task/transaction-integration/ingest-lambda`
- Related issue/PR: TBD
- Supersedes: on acceptance, [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md) decision 5 in part (the `appProtocol: grpc` on the auditor's port; decision 7 below)
- Superseded by: N/A

## Context

[ADR-0028](ADR-0028-cicd-migrations-terraform.md) (Proposed) puts all infrastructure in Terraform, but no infrastructure code existed. The team now needs to deploy the target architecture by hand from a developer machine, to test it, before any CI pipeline runs Terraform.

Building the stack also showed that the code on `dev` needs more from the deployment than the architecture documents describe:

- the Go services refuse to start in production mode without TLS certificate files, `DB_SSLMODE=verify-full` with a CA file, and `https://` URLs for the services they call;
- the auditor and chain-verify must not log in as the database owner ([ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) decision 2), but nothing creates their logins yet;
- some components are not deployable yet.

## Decision Drivers

- Deployable today, by hand, from a developer machine; CI comes later
- Fits the USD 300 project limit
- Follows the accepted ADRs, and records every deviation
- Least privilege; no secret in git, images or task definitions
- Every team member can explain it

## Options Considered

### Account plan

- **The paid plan only (chosen).** The stack deploys as the accepted ADRs describe it. The stop model (decision 4) and the budgets (decision 10) keep spending within the USD 300 limit.
- **A `free_plan` switch for accounts on the AWS Free account plan.** Built first, then removed on 15 October 2026 after a real apply. The Free plan refuses RDS backup retention above 1 day and instance classes above micro (`FreeTierRestrictionError`), offers no GuardDuty trial, and closes the account when its credits run out. It also caps how many RDS instances can exist at once (`InstanceQuotaExceeded`): the test account was allowed 4, and a day later 2, against the six this stack needs. AWS does not document these limits, and they changed during testing, so a second mode was not worth keeping.

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
3. **The paid plan.** The stack needs an AWS account on the paid plan; there is no Free-plan mode. Its sizes:

   | Setting | Value |
   |---|---|
   | RDS | `db.t4g.micro` ([ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md)), 20 GiB of gp3 that grows to 100 GiB, 7 days of point-in-time recovery |
   | RDS Multi-AZ | Only in the demo week ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)) |
   | DynamoDB `client-keys` | On-demand, point-in-time recovery on |
   | AWS Backup with a cross-region copy | Prod only; the Sydney vault uses a customer-managed KMS key so encrypted RDS recovery-point copies do not fall back to the incompatible `aws/backup` key |
   | WAF | Prod only (ADR-0029) |
   | GuardDuty, CloudTrail data events on the anchor buckets | On |
   | Tasks per service | 2–6 while running, 0 while stopped |
   | Log retention | 30 days |
   | SFTP server instance | `t4g.micro` |

   The tests in `stacks/crm/tests/` plan the stack against mocked providers and check these values.
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
   - the transaction-ingest Lambda (`enable_ingest_lambda`, off): since INF-04 the receiver's poller runs as a Lambda function and reads its secrets from Secrets Manager at start, but it has not run in AWS yet. A deployment turns it on with the zip from `make lambda` and the sftp-server stack's address and host key fingerprint ([runbook](../runbooks/deploy-aws.md#8-the-external-sftp-server-and-the-ingest-lambda) §8);
   - the health monitor: it is not runnable yet;
   - the alert worker, its schedule, AgentCore in Tokyo and Bedrock access: EDA-D1-02 (Azeez);
   - the GitHub deploy role (`enable_github_deploy_role`, off) and any CI checks on Terraform: deployments are run by hand for now.
10. **Smaller choices:**
    - the audit queues use SQS-managed encryption rather than a customer-managed key, so producers need no KMS permissions;
    - the audit queue's policy refuses `SendMessage` from every role except the producers and the replay role;
    - the anchor bucket refuses writes from everyone except chain-verify;
    - transaction-service, which checks no token, accepts connections from user-service only.
    - Lambda does not retry a failed transaction-ingest run, and drops a run throttled for more than 5 minutes behind a long one: the next scheduled run picks the files up, and retries would count toward ADR-0024's circuit breaker;
    - the budget alerts count cost before credits, which AWS Budgets subtracts by default, so they fire even when credits cover the bill. `crm-total` is one yearly budget from the project's start date, because ADR-0029's USD 300 is a limit for the whole project, not for each month;
    - the Sydney backup vault uses a dedicated customer-managed KMS key because RDS recovery points are not independently encrypted by AWS Backup and cannot use the destination vault's default `aws/backup` key for the cross-region copy.

## Rationale

Requiring the paid plan keeps one configuration, the one the accepted ADRs describe, instead of a second mode whose limits AWS does not document and which changed while it was being tested. The init container gets the current code running without code changes or secrets in images, and leaves room to remove it once services read PEM text directly. Native S3 locking needs nothing besides the bucket. Removing the NAT gateways between sessions saves most of the idle cost, without moving the address the external server trusts.

## Consequences

### Positive

- One configuration to test, explain and deploy.
- Each toggle's effect is tested without an AWS account.
- Deployments are repeatable, and teardown is `terraform destroy`.

### Negative / Trade-offs

- Every deployment is billed. The account must be on the paid plan, and the stack must be stopped between sessions to stay within the USD 300 limit (see Cost Impact).
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

Rough ap-southeast-1 figures in USD a month for prod; verify them with the AWS Pricing Calculator:

| | Running 24/7 | Stopped (`running = false`, RDS stopped) |
|---|---|---|
| NAT gateway | ~43 | 0 (Elastic IP ~4) |
| Internal ALB | ~20 | ~20 |
| 6 × RDS `db.t4g.micro`, single-AZ | ~125 | ~17 (storage) |
| Fargate, 7 services × 2 tasks | ~220 | 0 |
| WAF | ~10 | ~10 |
| GuardDuty, AWS Backup, CloudTrail data events | ~5 | ~5 |
| KMS, Secrets Manager, CloudWatch, API Gateway, CloudFront, other | ~21 | ~11 |
| **Total** | **~446** | **~66** |

Running 24/7 would pass the USD 300 limit within a month. With two 10-hour sessions a week and the stack stopped in between, prod costs about USD 110 a month, so the stop model is required, not optional. Staging costs less while it runs (Fargate Spot, no WAF or AWS Backup) and is destroyed after each promotion test (ADR-0029).

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
- `terraform test` in `stacks/crm`: six runs plan the stack against mocked providers and check prod, the high-availability week, the stopped state, staging, the Lambda settings from ADR-0024 and ADR-0040, and teardown.
- The first real apply on a paid-plan account must finish, and the services must pass their ALB health checks.

## References

- AWS: Choosing a plan (Free account plan terms), AWS Billing and Cost Management User Guide
- Reported Free plan limits: `FreeTierRestrictionError` on RDS backup retention above 1 day and on non-micro instance classes (GitHub issues yc-software/qm#352, aaronpotter/terraform-aws#18)
- [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md), [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md), [ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md), [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md), [ADR-0026](ADR-0026-one-stack-per-legal-entity.md), [ADR-0028](ADR-0028-cicd-migrations-terraform.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0040](ADR-0040-cognito-triggers-java-lambda.md), [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md)

## Revision history

- 2026-10-14 21:38 +08:00: proposed.
- 2026-10-16: amended while Proposed, as decided by the decision owner, after INF-04 gave the receiver a Lambda handler:
  - **Decision 9:** the transaction-ingest Lambda is still off by default, but no longer because the code cannot run on Lambda. The receiver's poller now runs as a Lambda function, reads its database login, SSH private key and Cognito client secret from Secrets Manager at start, and verifies transactions-db against the RDS CA bundle packaged in its zip (`DB_SSLROOTCERT`). It waits for a deployment to turn it on and for INF-02 to test it.
  - **Decision 10:** Lambda's asynchronous retries of the scheduled run are off (`maximum_retry_attempts = 0`, `maximum_event_age_in_seconds = 300`).
- 2026-10-16: amended while Proposed, as decided by the decision owner, after INF-05 gave transaction-service a production image:
  - **Decision 9:** transaction-service leaves the not-deployed list. INF-05 made the receiver's Dockerfile build it by default, as a distroless image like account-service's; the development image is its `tools` target. The runbook builds and pushes it with the other images.
