# Infrastructure — Terraform deployment of the target architecture

## Plan metadata

- Status: In progress (the CRM and sftp-server stacks are applied by hand to the team's paid-plan account, and the ingest Lambda imports files; Terraform CI is still pending)
- Primary developer: Neo Jia Wen
- Current working branch: `task/infrastructure/terraform`
- Last updated: 2026-10-20
- Related decisions: [ADR-0018](../adr/ADR-0018-ecs-fargate-compute.md), [ADR-0019](../adr/ADR-0019-edge-entry-chain.md), [ADR-0023](../adr/ADR-0023-rds-per-service-backup-no-cache.md), [ADR-0026](../adr/ADR-0026-one-stack-per-legal-entity.md), [ADR-0027](../adr/ADR-0027-observability-baseline.md), [ADR-0028](../adr/ADR-0028-cicd-migrations-terraform.md), [ADR-0029](../adr/ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0042](../adr/ADR-0042-terraform-deployment.md) (Terraform deployment), [ADR-0046](../adr/ADR-0046-human-deploy-access-mfa-role.md) (human deploy access), [ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md) (the business-dashboard stack)

## Goal

Deploy the target architecture ([system overview](../architecture/system-overview.md)) to AWS from Terraform, on an account on the paid plan, so the team can test it end to end before CI deploys it.

## Scope

- `infrastructure/terraform/`: the bootstrap stack, the CRM stack per legal entity and environment, the business dashboard's stack beside each CRM stack, and the external SFTP server's stack.
- The CRM's domain: registered by hand in Route 53, with the CRM stack's certificates and records in its hosted zone.
- The services that run on `dev` today: user-service, client-service (`client-profile-management-service`), account-service, the auditor and chain-verify, transaction-service (the SFTP receiver's read API), dashboard-service, the event-alert-service API, and the Cognito triggers.
- Out of scope for now: CI checks or deploy workflows for Terraform; the alert worker and its Tokyo search (EDA-D1-02).

## Acceptance criteria

- `terraform validate` passes for every stack, and `terraform test` passes in `stacks/crm` and `stacks/business-dashboard`.
- On a paid-plan account, `terraform apply` finishes for every stack.
- After the runbook's one-time steps, every service passes its ALB health check, a user signs in through managed login with MFA, and a client update reaches the audit chain.
- With `domain_name` set, the app answers on `https://sg.<domain>`, and API Gateway reaches the internal ALB over HTTPS.
- `running = false` leaves no tasks, schedules or NAT gateways running, and the SFTP server's allow-list still matches after `running = true`.
- [The runbook](../runbooks/deploy-aws.md) is enough for another team member to deploy and tear down.

## Tasks

| Task | Description | Owner | Expected files/components | Dependencies | Branch | Status |
|---|---|---|---|---|---|---|
| INF-01 | Terraform for the target architecture: bootstrap, CRM and SFTP-server stacks, the `postgres` and `ecs-service` modules, plan tests, the runbook and `scripts/aws-session.sh` | Neo Jia Wen | `infrastructure/terraform/**`, `scripts/aws-session.sh`, `docs/runbooks/deploy-aws.md`, ADR-0042 | None | `task/infrastructure/terraform` | Written; validated and plan-tested with mocked providers; not yet applied to AWS |
| INF-02 | First apply to a test account, then fix what the real apply finds | Neo Jia Wen | `infrastructure/terraform/**` | INF-01, images pushed | `task/infrastructure/terraform` | In progress: the CRM and sftp-server stacks are applied to the team's paid-plan account, with the fixes those applies found merged into `dev`, and the ingest Lambda imports files (2026-10-20) |
| INF-03 | db-migrate: one migration task per service (Flyway, golang-migrate), creating least-privilege application logins, replacing the runbook's hand-run SQL and the master-user logins (ADR-0028 decision 3, open item 8) | Unassigned | Each Go service's migrations, `stacks/crm/services.tf` | Open item 8 | TBD | Not started |
| INF-04 | Transaction ingest as a Lambda: a handler for the receiver's poll, reading the database, SSH key and Cognito client secret from Secrets Manager at start (ADR-0024 security), built as `bootstrap` for `provided.al2023` on arm64; then set `enable_ingest_lambda` | Feature 3 owner (Joseph Yau) to confirm | `services/sftp/receiver`, `services/sftp/Makefile`, `stacks/crm/lambda.tf` | INF-02 | `task/transaction-integration/ingest-lambda` | Written: the poller runs as a Lambda function, reads its secrets from Secrets Manager at init, and `make lambda` builds the zip; `make test` and the CRM plan tests pass, and the zip runs in the `provided.al2023` runtime emulator. Runs in AWS: it imports the SFTP server's files into transactions-db (confirmed 2026-10-20) |
| INF-05 | A production image for transaction-service (the receiver's read API), pushed to the `transaction-service` repository | Feature 3 owner (Joseph Yau) to confirm | `services/sftp/receiver/Dockerfile`, `services/sftp/compose.yaml`, `services/sftp/Makefile`, the runbook | None | `task/transaction-integration/ingest-lambda` | Written: the receiver's Dockerfile builds transaction-service by default (distroless, like account-service), and its tools stage stays the development image; the runbook builds and pushes it. Built and smoke-tested against a local database; not yet run in AWS (needs INF-02) |
| INF-06 | The alert worker's task and 4-hourly schedule, AgentCore in Tokyo and Bedrock access, using the CRM stack's `alerts_worker_inputs` output | Azeez (EDA-D1-02) | Alerts' own Terraform | INF-01 | `task/event-driven-alerts/search-infra` | Written as `stacks/alert-worker` (ADR-0048, Proposed); validates and passes 4 mocked-provider tests; the CRM stack's `alerts_worker_inputs` gained keys, none removed, and its 7 tests still pass; not yet applied to AWS. The Tokyo AgentCore gateway waits for the live spike |
| INF-07 | The health monitor's service, daily analysis task and health-db, once the service is runnable | Garvit, Swayam | `stacks/crm` | Health monitor RH-SF01 onwards | TBD | Not started |
| INF-08 | TLS between the ALB and the Java services (user-service, dashboard-service, event-alert-service), as the security model asks; then switch their target groups to HTTPS. Spring Boot serves the certificate `tls-init` writes, through `SERVER_SSL_*` variables the `ecs-service` module sets, so no Java code changes. user-service and event-alert-service get project-CA certificates; dashboard-service a self-signed one in its own stack ([ADR-0042](../adr/ADR-0042-terraform-deployment.md) decisions 5, 7 and 8, [ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md)) | Neo Jia Wen | `modules/ecs-service/**`, `stacks/crm/{tls,services,outputs}.tf`, `stacks/business-dashboard/{main,versions}.tf`, the runbook, ADR-0042, ADR-0047 | None | `task/infrastructure/java-services-tls` | Written; all stacks validate; CRM 7/7, business-dashboard 3/3 and ecs-service 2/2 mocked-provider tests pass; the Java init script and Spring Boot's TLS settings serve HTTPS in a local container as a non-root user; not yet applied to AWS |
| INF-09 | CI: build and push images, deploy through the GitHub OIDC role (`enable_github_deploy_role`), `terraform plan` with checkov (ADR-0028) | Unassigned | `.github/workflows/` | INF-02 | TBD | Not started; deferred on purpose |
| INF-10 | Review and repair the existing Terraform contribution without changing its architecture: resolve ADR-index merge artifacts, preserve the superseded Free-plan draft, run static and mocked-provider validation, and document remaining deployment blockers | Neo Jia Wen | `docs/adr/README.md`, ADR-0042 records, `infrastructure/terraform/**`, `docs/runbooks/deploy-aws.md`, cost/resource documentation, validation evidence | INF-01 | `task/infrastructure/terraform-review-fixes` | Complete locally: formatting and all stack validations pass; bootstrap 2/2 and CRM 6/6 mocked-provider tests pass; Checkov and live AWS plan/apply remain INF-02/INF-09 evidence |
| INF-11 | Human deploy access: the role `crm-infra-admin` (administrator, MFA required, 4-hour sessions) and the group `crm-deployers`, created by hand outside Terraform, with each operator's CLI setup and session commands in the runbook ([ADR-0046](../adr/ADR-0046-human-deploy-access-mfa-role.md)) | Neo Jia Wen | IAM console (by hand), `docs/runbooks/deploy-aws.md`, ADR-0046, `docs/architecture/stakeholders-and-asrs.md` | None | `task/infrastructure/deployer-access` | Role and group created in the account; MFA gate on access keys observed; a Terraform run under the role not yet recorded |
| INF-12 | Move dashboard-service out of the CRM stack into `stacks/business-dashboard`, which reads the CRM stack's `dashboard_inputs` output through `terraform_remote_state` and owns its image repository, security group and rules ([ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md)); business dashboard BD-T11 | Neo Jia Wen | `infrastructure/terraform/stacks/business-dashboard/**`, `stacks/crm/{services,security_groups,audit,ci,locals,outputs}.tf`, the runbook, `scripts/aws-session.sh`, ADR-0042, ADR-0047 | INF-01 | `task/infrastructure/business-dashboard-stack` | Written; all stacks validate; CRM 6/6 and business-dashboard 3/3 mocked-provider tests pass; not yet applied to AWS |
| INF-13 | Custom domain and HTTPS to the internal ALB (open item 2): a domain registered by hand in Route 53; with `domain_name` set, the CRM stack serves the app at `sg.<domain>` through a CloudFront alias, and gives the ALB an ACM certificate for `alb.sg.<domain>` that API Gateway verifies, with the ingest Lambda calling it by that name ([ADR-0042](../adr/ADR-0042-terraform-deployment.md) decision 8) | Neo Jia Wen | Route 53 console (by hand), `stacks/crm/{dns,api,edge,cognito,lambda,locals,variables,outputs}.tf`, the runbook, ADR-0042 | INF-01; `itsag3t6.com` in the project account | `task/infrastructure/custom-domain-https` | Written; all stacks validate; CRM 7/7 and business-dashboard 3/3 mocked-provider tests pass; not yet applied to AWS |

## Conflict zones

- `stacks/crm/services.tf` and `security_groups.tf` are shared by INF-03, INF-04, INF-07 and INF-08; change them on one branch at a time. `modules/ecs-service` is shared by every service.
- The alert worker (INF-06) belongs to EDA-D1-02. The CRM stack exposes `alerts_worker_inputs` and `extra_audit_producer_role_arns` for it instead of building it.
- The CRM stack's `dashboard_inputs` output is the contract with `stacks/business-dashboard` (ADR-0047). Renaming or removing one of its keys breaks that stack's plan; change both on one branch.
- ADR-0042 decision 7 changes ADR-0021 decision 5 on acceptance; the architecture pages that repeat it are updated then.

## Known gaps between the code and the target design

Recorded in ADR-0042:

- Most services log in as their database's master user until INF-03.
- Without `domain_name`, the internal ALB listens on HTTP inside the VPC and the app uses the default `*.cloudfront.net` address (INF-13).
- The auditor's `GET`, `POST` and `DELETE /api/corrupted-logs` stay until open item 13(e) is settled.
- The alert service defaults to global Nova 2 Lite inference from ap-southeast-1 with AI off (open item 4); the stack leaves AI and ingestion switched off.
