# Infrastructure — Terraform deployment of the target architecture

## Plan metadata

- Status: In progress (deployment tested by hand; no CI)
- Primary developer: Neo Jia Wen
- Current working branch: `task/infrastructure/terraform`
- Last updated: 2026-10-14
- Related decisions: [ADR-0018](../adr/ADR-0018-ecs-fargate-compute.md), [ADR-0019](../adr/ADR-0019-edge-entry-chain.md), [ADR-0023](../adr/ADR-0023-rds-per-service-backup-no-cache.md), [ADR-0026](../adr/ADR-0026-one-stack-per-legal-entity.md), [ADR-0027](../adr/ADR-0027-observability-baseline.md), [ADR-0028](../adr/ADR-0028-cicd-migrations-terraform.md), [ADR-0029](../adr/ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0042](../adr/ADR-0042-terraform-deployment.md) (Terraform deployment)

## Goal

Deploy the target architecture ([system overview](../architecture/system-overview.md)) to AWS from Terraform, on an account on the paid plan, so the team can test it end to end before CI deploys it.

## Scope

- `infrastructure/terraform/`: the bootstrap stack, the CRM stack per legal entity and environment, and the external SFTP server's stack.
- The services that run on `dev` today: user-service, client-service (`client-profile-management-service`), account-service, the auditor and chain-verify, transaction-service (the SFTP receiver's read API), dashboard-service, the event-alert-service API, and the Cognito triggers.
- Out of scope for now: CI checks or deploy workflows for Terraform; the alert worker and its Tokyo search (EDA-D1-02).

## Acceptance criteria

- `terraform validate` passes for every stack, and `terraform test` passes in `stacks/crm`.
- On a paid-plan account, `terraform apply` finishes for every stack.
- After the runbook's one-time steps, every service passes its ALB health check, a user signs in through managed login with MFA, and a client update reaches the audit chain.
- `running = false` leaves no tasks, schedules or NAT gateways running, and the SFTP server's allow-list still matches after `running = true`.
- [The runbook](../runbooks/deploy-aws.md) is enough for another team member to deploy and tear down.

## Tasks

| Task | Description | Owner | Expected files/components | Dependencies | Branch | Status |
|---|---|---|---|---|---|---|
| INF-01 | Terraform for the target architecture: bootstrap, CRM and SFTP-server stacks, the `postgres` and `ecs-service` modules, plan tests, the runbook and `scripts/aws-session.sh` | Neo Jia Wen | `infrastructure/terraform/**`, `scripts/aws-session.sh`, `docs/runbooks/deploy-aws.md`, ADR-0042 | None | `task/infrastructure/terraform` | Written; validated and plan-tested with mocked providers; not yet applied to AWS |
| INF-02 | First apply to a test account, then fix what the real apply finds | Neo Jia Wen | `infrastructure/terraform/**` | INF-01, images pushed | `task/infrastructure/terraform` | Not started |
| INF-03 | db-migrate: one migration task per service (Flyway, golang-migrate), creating least-privilege application logins, replacing the runbook's hand-run SQL and the master-user logins (ADR-0028 decision 3, open item 8) | Unassigned | Each Go service's migrations, `stacks/crm/services.tf` | Open item 8 | TBD | Not started |
| INF-04 | Transaction ingest as a Lambda: a handler for the receiver's poll, reading the database, SSH key and Cognito client secret from Secrets Manager at start (ADR-0024 security), built as `bootstrap` for `provided.al2023` on arm64; then set `enable_ingest_lambda` | Feature 3 owner (Joseph Yau) to confirm | `services/sftp/receiver` | INF-02 | TBD | Not started |
| INF-05 | A production image for transaction-service (the receiver's read API), pushed to the `transaction-service` repository | Feature 3 owner (Joseph Yau) to confirm | `services/sftp/receiver/Dockerfile` | None | TBD | Not started |
| INF-06 | The alert worker's task and 4-hourly schedule, AgentCore in Tokyo and Bedrock access, using the CRM stack's `alerts_worker_inputs` output | Azeez (EDA-D1-02) | Alerts' own Terraform | INF-01 | `task/event-driven-alerts/search-infra` | Blocked on accepted ADRs (alerts plan) |
| INF-07 | The health monitor's service, daily analysis task and health-db, once the service is runnable | Garvit, Swayam | `stacks/crm` | Health monitor RH-SF01 onwards | TBD | Not started |
| INF-08 | TLS between the ALB and the Java services (user-service, dashboard-service, event-alert-service), as the security model asks; then switch their target groups to HTTPS | Unassigned | Each Java service, `stacks/crm/services.tf` | None | TBD | Not started |
| INF-09 | CI: build and push images, deploy through the GitHub OIDC role (`enable_github_deploy_role`), `terraform plan` with checkov (ADR-0028) | Unassigned | `.github/workflows/` | INF-02 | TBD | Not started; deferred on purpose |

## Conflict zones

- `stacks/crm/services.tf` and `security_groups.tf` are shared by INF-03, INF-04, INF-07 and INF-08; change them on one branch at a time.
- The alert worker (INF-06) belongs to EDA-D1-02. The CRM stack exposes `alerts_worker_inputs` and `extra_audit_producer_role_arns` for it instead of building it.
- ADR-0042 decision 7 changes ADR-0021 decision 5 on acceptance; the architecture pages that repeat it are updated then.

## Known gaps between the code and the target design

Recorded in ADR-0042:

- Most services log in as their database's master user until INF-03.
- Java services serve plain HTTP to the ALB (INF-08).
- The auditor's `GET`, `POST` and `DELETE /api/corrupted-logs` stay until open item 13(e) is settled.
- The alert service defaults to a global-inference model in us-east-1 with AI off (open item 4); the stack leaves AI and ingestion switched off.
