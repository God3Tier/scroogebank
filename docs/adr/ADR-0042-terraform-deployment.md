# ADR-0042: Terraform deployment of the target architecture

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-10-14 21:38 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/terraform`; decisions 1, 3, 9 and 10 amended and decision 11 added on `task/transaction-integration/ingest-lambda`
- Related issue/PR: TBD
- Supersedes: on acceptance, [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md) decision 5 in part (the `appProtocol: grpc` on the auditor's port; decision 7 below)
- Superseded by: [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md) decision 1 in part, if accepted (`bootstrap` is applied and destroyed with every deployment)

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
   - `bootstrap/` holds account-wide resources, applied once per account with local state: the state bucket, CloudTrail, AWS Budgets and, behind a flag, the GitHub OIDC provider. It creates no GuardDuty detector, because the account already has one (decision 11).
   - `modules/postgres` and `modules/ecs-service` are one module per pattern (ADR-0028).
   - `stacks/crm` is one stack per legal entity and environment ([ADR-0026](ADR-0026-one-stack-per-legal-entity.md)), with state at `crm/<entity>/<environment>.tfstate`.
   - `stacks/sftp-server` is the external bank's SFTP server, in its own stack ([ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 1).
   - `stacks/business-dashboard` is dashboard-service, one stack per CRM stack, with state at `business-dashboard/<entity>/<environment>.tfstate`. It runs inside the CRM stack's network and cluster and reads that stack's `dashboard_inputs` output ([ADR-0047](ADR-0047-business-dashboard-terraform-stack.md)).
2. **State.** A versioned, KMS-encrypted, TLS-only S3 bucket with native locking. This settles ADR-0028 decision 4, which left the backend to be chosen when the infrastructure work started.
3. **The paid plan.** The stack needs an AWS account on the paid plan; there is no Free-plan mode. Its sizes:

   | Setting | Value |
   |---|---|
   | RDS | `db.t4g.micro` ([ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md)), 20 GiB of gp3 that grows to 100 GiB, 7 days of point-in-time recovery |
   | RDS Multi-AZ | Only in the demo week ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)) |
   | DynamoDB `client-keys` | On-demand, point-in-time recovery on |
   | AWS Backup | Prod only, in Singapore. The cross-region copy is left out (decision 11) |
   | WAF | Prod only (ADR-0029), without request logging (decision 11) |
   | GuardDuty | On, through the account's existing detector (decision 11) |
   | CloudTrail data events on the anchor buckets | On |
   | Tasks per service | 1–6 while running, 0 while stopped ([ADR-0018](ADR-0018-ecs-fargate-compute.md)) |
   | Log retention | 30 days |
   | SFTP server instance | `t4g.micro` |

   The tests in `stacks/crm/tests/` plan the stack against mocked providers and check these values.
4. **Stop model.** `running = false` scales every service to 0 tasks, pauses the schedules and **removes the NAT gateways**. Their Elastic IPs are separate resources and stay allocated, so a recreated NAT gateway gets the same address and the SFTP server's allow-list never changes. This refines ADR-0029, which kept the NAT gateway up for that reason. `scripts/aws-session.sh` stops and starts the RDS instances and the SFTP server's instance. `high_availability = true` is the demo week: Multi-AZ standbys and a NAT gateway per AZ ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)).
   - **Starting or stopping redeploys every service, for Service Connect.** A task can call another service through Service Connect only if that service existed when its own service's latest deployment was made, and scaling up makes no new deployment.
     - A new stack creates its services together, so their first deployments can miss each other. client-service and account-service call each other, so no order of creation avoids that.
     - A change in a service's minimum task count redeploys it (the provider's `triggers`, with `force_new_deployment`). On a start this happens at 0 tasks, before auto scaling starts any, so every task gets every name.
     - **Every stack is therefore created stopped** (`running = false`), as the runbook does. Its first start then redeploys every service once all of them exist, which replaces the runbook's restart of every service after that start.
5. **Certificates and trust.** Terraform's `tls` provider creates the project CA (ADR-0021 decision 7) and a certificate for each service in `stacks/crm` that serves TLS: user-service, client-service, account-service and the auditor, named for their Service Connect aliases, and event-alert-service, named for itself because only the internal ALB calls it. dashboard-service's certificate is self-signed in its own stack ([ADR-0047](ADR-0047-business-dashboard-terraform-stack.md)), so the CA's private key never leaves the CRM stack. The certificates and keys are kept in Secrets Manager. In each task, a `tls-init` container writes `/tls`:
   - `ca.pem`: the project CA;
   - `rds-ca.pem`: the RDS CA bundle for the region;
   - `ca-bundle.pem`: public roots plus both CAs, which Go reads through `SSL_CERT_FILE`;
   - `cert.pem` and `key.pem`: the service's own certificate;
   - for Java services, a JVM truststore that adds the project CA.

   Go services read the certificate and key through their own settings. For a Java service, the `ecs-service` module sets Spring Boot's `SERVER_SSL_CERTIFICATE` and `SERVER_SSL_CERTIFICATEPRIVATEKEY`, so the services need no code change and still serve plain HTTP locally. The private keys are also in the Terraform state, so only administrators may read the state bucket.
6. **Database logins.** No service connects as its instance's RDS-managed master user. Each has its own login without schema rights ([ADR-0028](ADR-0028-cicd-migrations-terraform.md) decision 3):
   - the auditor and chain-verify log in as `auditor_app` and `chain_verify`, holding the roles `auditor_service` and `auditor_chain_verify` (ADR-0022 decision 2);
   - user-service, client-service, account-service and event-alert-service each have a login that reads and changes rows;
   - transaction-service's login only reads `transactions`, and the ingest Lambda has its own login.

   Terraform generates each password and keeps it in its own Secrets Manager secret. The db-migrate container of the service that owns the database creates the logins and sets their passwords at every start. Only db-migrate and the database console receive the master credentials.
7. **Service Connect proxies TLS ports as TCP.** The auditor's gRPC and HTTPS ports, user-service, client-service and account-service have no `appProtocol`. Their TLS is end to end (ADR-0021 decision 7), so Envoy cannot read gRPC or HTTP inside it, and `appProtocol: grpc` (ADR-0021 decision 5) would break the connection. Envoy balances connections, and the server's `MaxConnectionAge` makes callers reconnect, as decision 7 already says. transaction-service, the only plain-HTTP service on Service Connect, keeps `appProtocol: http`.
8. **Internal ALB.**
   - **Custom domain and HTTPS** ([ADR-0019](ADR-0019-edge-entry-chain.md) decision 5, [open item 2](../architecture/open-items.md#2-domain-name)). The domain is registered by hand in Route 53, which creates its public hosted zone. Both stay outside Terraform, like the deploy role (ADR-0046), so no destroy removes them. With `domain_name` set, `stacks/crm`:
     - finds the zone by name;
     - serves the app at `<legal_entity>.<domain>` for prod and `<legal_entity>-<environment>.<domain>` otherwise (`sg.<domain>`, `sg-staging.<domain>`), through a CloudFront alias with an ACM certificate from us-east-1;
     - gives the internal ALB an ACM certificate for `alb.<app host>`, and listens on HTTPS 443 with `ELBSecurityPolicy-TLS13-1-2-2021-06`. API Gateway verifies that name on the VPC Link connection, and the ingest Lambda calls the ALB by it;
     - validates both certificates through DNS records in the zone, and keeps the `*.cloudfront.net` origin in Cognito's callback URLs, so both addresses sign in.

     The ALB's name is a public record that resolves to its private addresses in the app subnets, which nothing outside the VPC can reach. A private hosted zone would cost another USD 0.50 a month and protect nothing more. Without `domain_name`, the ALB listens on HTTP 80 inside the VPC and the app uses the default CloudFront name, which the mocked tests and an account without a domain still use. The project account's domain is `itsag3t6.com`; the runbook sets `domain_name` only in an account that has its hosted zone, so a personal test account deploys without one.
   - **HTTPS to every target** (INF-08). All five target groups, and their health checks, use HTTPS, which meets the security model's "self-signed TLS between the ALB and tasks". The ALB encrypts to its targets but does not check their certificates, so a certificate's name and issuer matter only to Service Connect callers, such as dashboard-service calling user-service.
9. **Not deployed yet, each a task in `docs/feature-plans/infrastructure_plan.md`:**
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
    - the Sydney backup vault, when restored (decision 11), uses a dedicated customer-managed KMS key because RDS recovery points are not independently encrypted by AWS Backup and cannot use the destination vault's default `aws/backup` key for the cross-region copy.
11. **Left out because of the account.** The account the stack is deployed to already has, or refuses, three parts of the design:
    - **GuardDuty detector:** the account already has one, and GuardDuty allows one per account per Region, so `bootstrap/` does not create one. The existing detector provides ADR-0027 decision 5's threat detection. It is outside Terraform, so the team neither changes nor destroys it.
    - **Cross-region backup copy:** the account's service control policy denies `backup:CreateBackupVault` in ap-southeast-2. The Sydney KMS key, the Sydney vault, its Vault Lock and the backup plan's copy are commented out in `stacks/crm/backup.tf`, with their test assertion.
    - **WAF request logging:** the service control policy denies `logs:CreateLogGroup` in us-east-1, where CloudFront's WAF and its log group must be. The log group and the web ACL's logging configuration are commented out in `stacks/crm/edge.tf`. The web ACL itself is deployed.

    The commented-out code stays, to restore when the policy allows it.

## Rationale

Requiring the paid plan keeps one configuration, the one the accepted ADRs describe, instead of a second mode whose limits AWS does not document and which changed while it was being tested. The init container gets the current code running without code changes or secrets in images, and leaves room to remove it once services read PEM text directly. Native S3 locking needs nothing besides the bucket. Removing the NAT gateways between sessions saves most of the idle cost, without moving the address the external server trusts.

## Consequences

### Positive

- One configuration to test, explain and deploy.
- Each toggle's effect is tested without an AWS account.
- Deployments are repeatable, and teardown is `terraform destroy`.

### Negative / Trade-offs

- Every deployment is billed. The account must be on the paid plan, and the stack must be stopped between sessions to stay within the USD 300 limit (see Cost Impact).
- Starting a session takes longer: the NAT gateway is recreated (about 2–3 minutes) and the RDS instances start (several minutes).
- Every start and stop redeploys every service (decision 4). Plans show each service updating in place, and a stop briefly starts one new task per service before scaling it to 0.
- A stack created with `running = true` starts its tasks on deployments that can miss other services' names (decision 4). Its callers fail with `UnknownHostException` until they are redeployed.
- The CA and service private keys are in the Terraform state.
- Without the cross-region copy (decision 11), backups exist only in ap-southeast-1, and no Vault Lock protects them. [ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md)'s recovery by rebuilding in ap-southeast-2, and the restore rehearsal it plans, cannot be done until the policy allows the vault.
- Without WAF request logging (decision 11), nothing records which requests WAF blocked, or why; only WAF's CloudWatch metrics and sampled requests remain.
- The GuardDuty detector is not in the code, so a new account needs one enabled by hand.
- ADR-0021 decision 5 and the architecture pages that repeat it (data-flow "Audit read decisions", system-overview "Parts of the architecture not shown on the diagram") need updating when this ADR is accepted.

## Security & Privacy Impact

- No secret is in git, images or task definitions: tasks read Secrets Manager through per-service execution roles.
- The state bucket holds private keys, so access to it must stay restricted to administrators.
- Every task role and the queue, anchor and documents bucket policies follow least privilege.
- With a domain, API Gateway and the ingest Lambda reach the internal ALB over TLS 1.2 or 1.3, and API Gateway checks the certificate's name. Without one, the ALB's plain HTTP inside the VPC is reachable only from the VPC Link and the ingest Lambda.
- The ALB reaches every service over TLS, with or without a domain. It does not check the services' certificates; the security groups, which admit only the ALB and named callers, keep anything else from answering in their place.
- user-service still calls transaction-service over plain HTTP inside the VPC, through Service Connect; that call does not pass the ALB.
- The ALB's DNS name is public and shows its private addresses, but gives no route to them.

## Cost Impact

Rough ap-southeast-1 figures in USD a month for prod; verify them with the AWS Pricing Calculator:

| | Running 24/7 | Stopped (`running = false`, RDS stopped) |
|---|---|---|
| NAT gateway | ~43 | 0 (Elastic IP ~4) |
| Internal ALB | ~20 | ~20 |
| 6 × RDS `db.t4g.micro`, single-AZ | ~125 | ~17 (storage) |
| Fargate, 7 services × 1 task | ~132 | 0 |
| WAF | ~10 | ~10 |
| GuardDuty, AWS Backup, CloudTrail data events | ~5 | ~5 |
| KMS, Secrets Manager, CloudWatch, API Gateway, CloudFront, other | ~21 | ~11 |
| **Total** | **~358** | **~66** |

The Fargate row is the minimum; tasks added by auto scaling under load cost more. Running 24/7 would pass the USD 300 limit within a month. With two 10-hour sessions a week and the stack stopped in between, prod costs about USD 100 a month, so the stop model is required, not optional. Staging costs less while it runs (Fargate Spot, no WAF or AWS Backup) and is destroyed after each promotion test (ADR-0029).

Each service certificate's secret costs USD 0.40 a month, running or stopped. The three added for user-service, event-alert-service and dashboard-service add USD 1.20 a month (INF-08). The six database logins added for the services other than the auditor (decision 6) add USD 2.40 a month (INF-03).

The project account's domain, `itsag3t6.com`, is already paid until July 2027, so the custom domain adds only its hosted zone: USD 0.50 a month, running or stopped. A domain registered for another account costs about USD 15 a year for a `.com`, which AWS credits do not cover. The ACM certificates are free.

## Operational Impact

`docs/runbooks/deploy-aws.md` covers:

- bootstrap, and registering the domain by hand;
- building and pushing images and the Lambda zip;
- the apply order (crm, then business-dashboard, then sftp-server, then crm again);
- creating each stack stopped, so the first start lets the services call each other (decision 4);
- the frontend upload;
- starting and stopping sessions;
- teardown.

`scripts/aws-session.sh` stops and starts the RDS instances and the SFTP server.

## Validation

- `terraform validate` on all four stacks.
- `terraform test` in `stacks/crm`: seven runs plan the stack against mocked providers and check prod, the custom domain, the high-availability week, the stopped state, staging, the Lambda settings from ADR-0024 and ADR-0040, and teardown.
- `terraform test` in `modules/ecs-service`:
  - two runs check that a Java service serving TLS gets its certificate files, Spring Boot's TLS settings, a TCP Service Connect port and HTTPS health checks, and that one that does not stays on HTTP;
  - four check the db-migrate container ([ADR-0028](ADR-0028-cicd-migrations-terraform.md));
  - two check that a change in the minimum task count redeploys the service (decision 4).
- `terraform test` in `stacks/business-dashboard`: see [ADR-0047](ADR-0047-business-dashboard-terraform-stack.md).
- **Done (2026-10-20, confirmed by the decision owner):**
  - The first real apply on a paid-plan account finished.
  - user-service, event-alert-service and dashboard-service pass their ALB health checks over HTTPS.
  - With `domain_name`, the app answers at `https://sg.itsag3t6.com`, and API Gateway reaches the ALB over HTTPS.
- **Still to check:**
  - The first start of a new stack, created stopped, must leave every service able to call the others with no restart: no `UnknownHostException` in any log group.
  - Every service and the ingest Lambda must run under their own logins (decision 6): no `permission denied` or `password authentication failed` in any log group.

## References

- AWS: Choosing a plan (Free account plan terms), AWS Billing and Cost Management User Guide
- Reported Free plan limits: `FreeTierRestrictionError` on RDS backup retention above 1 day and on non-micro instance classes (GitHub issues yc-software/qm#352, aaronpotter/terraform-aws#18)
- [ADR-0019](ADR-0019-edge-entry-chain.md), [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md), [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md), [ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md), [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md), [ADR-0026](ADR-0026-one-stack-per-legal-entity.md), [ADR-0028](ADR-0028-cicd-migrations-terraform.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0040](ADR-0040-cognito-triggers-java-lambda.md), [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md)

## Revision history

- 2026-10-14 21:38 +08:00: proposed.
- 2026-10-16: amended while Proposed, as decided by the decision owner, after INF-04 gave the receiver a Lambda handler:
  - **Decision 9:** the transaction-ingest Lambda is still off by default, but no longer because the code cannot run on Lambda. The receiver's poller now runs as a Lambda function, reads its database login, SSH private key and Cognito client secret from Secrets Manager at start, and verifies transactions-db against the RDS CA bundle packaged in its zip (`DB_SSLROOTCERT`). It waits for a deployment to turn it on and for INF-02 to test it.
  - **Decision 10:** Lambda's asynchronous retries of the scheduled run are off (`maximum_retry_attempts = 0`, `maximum_event_age_in_seconds = 300`).
- 2026-10-16: amended while Proposed, as decided by the decision owner, after INF-05 gave transaction-service a production image:
  - **Decision 9:** transaction-service leaves the not-deployed list. INF-05 made the receiver's Dockerfile build it by default, as a distroless image like account-service's; the development image is its `tools` target. The runbook builds and pushes it with the other images.
- 2026-10-16: amended while Proposed, as decided by the decision owner, after the first applies to the team's account:
  - **Decision 11 (new):** the GuardDuty detector, the cross-region backup copy and WAF request logging are left out, because the account already has a detector and its service control policy denies the other two.
  - **Decisions 1, 3 and 10:** changed to match.
- 2026-10-16: amended while Proposed, as decided by the decision owner, to cut Fargate cost:
  - **Decision 3:** services run 1–6 tasks while running instead of 2–6, with [ADR-0018](ADR-0018-ecs-fargate-compute.md) amended to match. Cost Impact changed to match.
- 2026-10-19: Cost Impact updated, as decided by the decision owner: dashboard-service runs on 1 vCPU and 2 GB instead of 0.5 vCPU and 1 GB. On 0.5 vCPU, shared with its Service Connect proxy, the JVM took 34 s to start and 1.5–3.4 s for its first call to each upstream, so the first dashboard after a start timed out. About USD 22 a month more running 24/7, about USD 3 a month at two 10-hour sessions a week.
- 2026-10-20: amended while Proposed, as decided by the decision owner: dashboard-service moves out of `stacks/crm` into `stacks/business-dashboard` ([ADR-0047](ADR-0047-business-dashboard-terraform-stack.md)). Decision 1, Operational Impact and Validation changed to match. Cost Impact is unchanged: the same resources run.
- 2026-10-20: amended while Proposed, as decided by the decision owner, which resolves open item 2:
  - **Decision 8:** a domain registered by hand in Route 53. With `domain_name` set, the app is served at `sg.<domain>` and the internal ALB listens on HTTPS with an ACM certificate that API Gateway verifies. This replaces the `internal_alb_certificate_arn` and `internal_alb_server_name` variables. Security & Privacy Impact, Cost Impact, Operational Impact and Validation changed to match.
  - **Decision 9:** the transaction-ingest Lambda leaves the not-deployed list. Runbook §9.3 turns it on, and it imports the SFTP server's files into transactions-db in AWS, as the decision owner confirmed on 2026-10-20.
- 2026-10-20: amended while Proposed, as decided by the decision owner (INF-08):
  - **Decision 5:** user-service and event-alert-service get project-CA certificates, and dashboard-service a self-signed one in its own stack. Java services serve them through Spring Boot's `server.ssl` settings.
  - **Decision 7:** user-service's port becomes TCP on Service Connect; only transaction-service keeps `appProtocol: http`.
  - **Decision 8:** every target group uses HTTPS, which closes the gap with the security model. Security & Privacy Impact, Cost Impact and Validation changed to match.
- 2026-10-20: amended while Proposed, as decided by the decision owner (INF-03).
  - **Decision 6:** the auditor's db-migrate container creates the auditor's and chain-verify's logins ([ADR-0028](ADR-0028-cicd-migrations-terraform.md) decision 3), and the runbook no longer applies any SQL by hand.
  - Consequences, Operational Impact and Validation changed to match.
- 2026-10-20: amended while Proposed, as decided by the decision owner:
  - **Decision 4:** starting or stopping the stack redeploys every service, so every task gets every Service Connect name, and every stack is created stopped. This replaces the runbook's restart of every service after a new stack's first start.
  - Consequences, Operational Impact and Validation changed to match. Cost Impact is unchanged: a stop's extra tasks run for seconds.
- 2026-10-20: amended while Proposed, as decided by the decision owner (INF-03, application logins):
  - **Decision 6:** every service, and the ingest Lambda, connects with its own login without schema rights, which ends the deviation from ADR-0028 decision 3.
  - Consequences, Cost Impact (USD 2.40 a month for the secrets) and Validation changed to match. Validation also records the first apply's results, which the decision owner confirmed.
