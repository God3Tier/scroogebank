# Scrooge Global Bank CRM

Secure, cloud-native back-office CRM for a global bank (CS301 project).
Supports Admin and Agent roles across client profiles, accounts, transactions, and innovation features (event-driven alerts, client health monitoring, business dashboard).

## 1. Testing the current (deployed) setup

**Production app:** https://sg.itsag3t6.com

### Evaluation accounts

The deployed stack uses the brief's evaluation accounts, `admin@crm.com` and `agent1@crm.com` ([ADR-0076](docs/adr/ADR-0076-evaluation-accounts-without-mfa.md)). They sign in with a password only (no MFA). If passwords were not set yet, run:

```
scripts/evaluation-accounts.sh infrastructure/terraform/stacks/crm
```

It prompts for both passwords (12+ characters, upper/lower/number/symbol) and never stores them.

### Suggested test flows

Sign in as `admin@crm.com` or `agent1@crm.com`.

**Agent flow** (`agent1@crm.com`):
- Open Agent Home → Manage Client Profiles and Accounts.
- Create a client with Create Client Profile.
- Upload an identity document (JPEG/PNG/PDF, ≤5 MB) and verify the client.
- Open an account (Pending until client verified; Active after verification).

**Admin flow** (`admin@crm.com`):
- Manage Agents → Reassign clients (bulk) or Reassign selected clients (multi-select).
- Open a client profile → Agent panel → Reassign to move one client.
- View dashboards at `/insights/admin` and `/insights/agent`.
- Client Health: `/admin/client-health` (admin) or `/agent/client-health` (agent).
- Event Alerts: `/admin/alerts` (read-only) or `/agent/alerts`.

### Notes

- All data is synthetic; never use real PII.
- If a service is unavailable, the UI shows the reason and a retry option.

## Tech stack

- **Frontend:** React 19, Vite, React Router v7, `react-oidc-context`/`oidc-client-ts` (Cognito OIDC sign-in), Recharts (dashboards)
- **Backend:** Go 1.27 (account-service, client-profile-management-service, auditor); Java 25/Spring Boot (user-, dashboard-, engagement-, client-health-monitor-, event-alert-service, cognito-triggers)
- **Data:** PostgreSQL on RDS (one instance per service, Multi-AZ standby); DynamoDB (per-client field-encryption keys)
- **Messaging:** Amazon SQS (audit events); gRPC over ECS Service Connect (audit reads)
- **AI:** Amazon Bedrock (Nova Pro / Nova Lite) for event-alert report drafts and engagement follow-up drafts
- **Infrastructure:** AWS ECS Fargate, API Gateway, CloudFront, S3, Cognito, WAF, Route 53, Terraform; GitHub Actions for CI (deployment is manual, by runbook)
- **External services:** Tavily (news search), Amazon SES (mail), data.gov.sg (public holidays)

## 2. Setting up a new instance

### Prerequisites

- Node.js 18+ and npm
- Go 1.27+ (for the Go services: account, client-profile-management, auditor)
- Java 25 (for the Java/Spring Boot services)
- PostgreSQL 15+
- AWS account with a Cognito user pool and required permissions
- Terraform 1.6+ (for AWS deployment)

### Local development (quick start)

Clone the repo:

```
git clone https://github.com/cs301-itsa/project-2026-27t1-g3-t6.git
cd project-2026-27t1-g3-t6
```

Frontend:

```
cd frontend
npm install
cp .env.example .env
# Fill VITE_COGNITO_* values (see frontend/README.md)
npm run dev
```

Backend services — each has its own README with full setup (database, Cognito, and other env vars):

- `services/client-profile-management-service` (Go, port 8080)
- `services/user-service` (Java, port 8081)
- `services/account-service` (Go, port 8083)
- `services/auditor` (Go; consumes the audit SQS queue, no HTTP port)
- `services/dashboard-service` (Java, port 8085)
- `services/engagement-service` (Java, port 8086)
- `services/client-health-monitor-service` (Java, port 8087)
- `services/event-alert-service` (Java; `ALERT_MODE=API` or `ALERT_MODE=WORKER`)
- `services/cognito-triggers` (Java; Cognito Lambda triggers, not a standalone server)
- `services/sftp` (the SFTP receiver used for the transaction import)

> `transaction-service` is deployed by Terraform ([`infrastructure/terraform/stacks/crm/services.tf`](infrastructure/terraform/stacks/crm/services.tf)) and referenced throughout the docs and ADRs, but its source is not in this repository — there is no `services/transaction-service` directory to run locally. Treat anything that depends on it (e.g. transaction history) as AWS-only until that's resolved.

Run tests:

```
cd frontend && npm test
# Go services
cd services/client-profile-management-service && make test
```

### AWS deployment

Follow the detailed runbook: [docs/runbooks/deploy-aws.md](docs/runbooks/deploy-aws.md)

- Terraform stacks under `infrastructure/terraform/stacks/` (`crm`, `alert-worker`, `agent-engagement`, `business-dashboard`, `mail-identity`, `sftp-server`)
- Domain: `sg.itsag3t6.com`, under the registered `itsag3t6.com` zone in Route 53

### Configuration

- Never commit secrets; use environment variables and AWS Secrets Manager.
- Most services require `COGNITO_ISSUER_URI`, `COGNITO_APP_CLIENT_ID`, `AWS_REGION`, and `AUDIT_QUEUE_URL` — see each service's README for its full variable list (names vary slightly by service).
- `services/client-profile-management-service/localstack/init-client-keys.sh` creates the encryption keys and document bucket for local dev.

## Documentation

- [docs/adr/](docs/adr/) — architecture decision records
- [docs/api/](docs/api/) — OpenAPI contracts

For detailed per-service setup, see each service's `README.md`.
