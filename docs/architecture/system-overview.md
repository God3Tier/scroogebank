# Target architecture: system overview

> **Status:** Accepted target architecture (2026-11-01), checked against the code on `dev` that day. **Owner:** Neo Jia Wen. **Last updated:** 2026-11-01.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

This page is the entry point to the CRM's target AWS architecture. It replaces the team's `ARCHITECTURE.docx` as the reviewable source in the repository.

## Document map

| Page | Contents |
|---|---|
| This page | Summary, how to read the diagram, components, why each AWS service is included, parts not on the diagram, network, compute choice |
| [Request, audit and ingestion flows](data-flow.md) | Numbered flows 1–15, audit write path (outbox + SQS), audit read path (gRPC), transaction ingestion (SFTP + Lambda) |
| [Security model](../security/security-model.md) | Identity, authorization, perimeter, encryption, audit tamper resistance, GDPR erasure, authentication events, client data envelope encryption |
| [Database design](../database/schema.md) | Databases and owners, one RDS instance per service, AWS Backup |
| [Schema migrations](../database/migrations.md) | The db-migrate init container in each service's task |
| [X-factor features](x-factors.md) | Market-news alerts, Agent Engagement, Client Relationship Health Monitor, and the additional business dashboard (Feature 7) |
| [NFR mapping](nfr-mapping.md) | How each non-functional requirement (NFR 1–13) is met |
| [Test strategy](../testing/test-strategy.md) | How the design will be evaluated |
| [Environments and cost](../cost/budget.md) | dev, staging and prod; rough monthly cost; budget levers |
| [Decision log](decision-log.md) | Every major decision: chosen, not chosen and why, with its ADR |
| [Open items](open-items.md) | Decisions still needed from the team, with owners |
| [ADR index](../adr/README.md) | ADR-0017 onwards record these decisions, with the feature lines' own ADRs; the index gives each one's status |

## Summary

- **Region and topology:** one AWS region (`ap-southeast-1`, Singapore), two Availability Zones, and one full stack per legal entity.
- **Frontend:** static frontend files in a private S3 bucket, served by CloudFront with AWS WAF in front.
- **API entry:** all `/api/*` traffic goes through API Gateway (which validates the Cognito JWT and applies throttling), then through a VPC Link to an internal Application Load Balancer.
- **Business services:** containers on **ECS Fargate**, one microservice per business area: user-service, client-service, account-service, transaction-service, the three x-factor services (event-alert-service, engagement-service, client-health-monitor-service), dashboard-service (Feature 7) and the auditor. Each runs at least one task and scales out across both AZs (up to 6; 0 while the stack is stopped).
- **X-factors (Features 4–6):** event-driven market-news alerts, Agent Engagement, and a Client Relationship Health Monitor ([x-factor features (Features 4–6)](x-factors.md)). Only public search terms leave Singapore, for a news search through the third-party Tavily API, and the alert model receives only public data. The health monitor's banded facts stay in Asia-Pacific; no client personal data leaves Singapore.
- **X-factor 2 (Feature 5): Agent Engagement.** engagement-service (Java 25 on ECS), with its own engagement-db and its own Terraform stack, puts client meetings in agents' own mail calendars through e-mailed iCalendar invites, sends reminders, and drafts follow-up e-mails with Amazon Nova Lite, including follow-ups from a Client Health task or an alert, which the agent edits and sends. Mail goes out through Amazon SES ([Agent Engagement](x-factors.md#x-factor-2-agent-engagement-feature-5)).
- **Databases:** each service has its **own RDS for PostgreSQL instance**, with a **Multi-AZ standby** in the second AZ. This balances isolation against cost ([Databases](../database/schema.md#databases)). Client personal data is encrypted field by field with a per-client key, and those keys are kept in a separate **DynamoDB** table, wrapped by a symmetric **KMS** key ([Client data encryption (envelope encryption)](../security/security-model.md#client-data-encryption-envelope-encryption)).
- **Auditor:** an **internal-only** service; the frontend never calls it.
  - **Writes** reach it asynchronously through one shared **Amazon SQS standard** queue (`audit-insert`). Services with a database use an outbox in their own database, written in the business transaction by default (user-, client- and account-service save it right after the request); the Cognito triggers and dashboard-service, which have none, send directly.
  - **Reads** use synchronous **gRPC** calls from other services, through **ECS Service Connect**.
  - A daily **chain-verify** task checks the hash chain and anchors its head in an **S3 Object Lock** bucket.
- **Transaction import:** a scheduled **Lambda function** connects out over SSH/SFTP to the external core banking SFTP server (simulated on an EC2 instance), reads files marked as ready, and imports them.
- **Identity:** **Amazon Cognito** handles sign-in, with mandatory MFA and Admin/Agent groups. Only the two evaluation accounts the brief requires sign in without MFA; a token trigger enforces it for everyone else ([ADR-0076](../adr/ADR-0076-evaluation-accounts-without-mfa.md)).

## Architecture diagram

The diagram is kept outside the repository, in the team's Google Drive: `(NEW) AWS Architecture Diagram.drawio` (full version, with the numbered-flows legend) and `(NEW) AWS Architecture Diagram - Compact.drawio`. Its numbered arrows match the [numbered flows](data-flow.md#numbered-flows). It follows the code: task counts 1–6, db-migrate as an init container, the audit outbox, SFTP files in `reports/`, identity documents through client-service, the shared transaction codebase, client field encryption, the engagement (F7) column with Amazon SES and data.gov.sg, admin client reassignment, the db-console task, the tls-init note, and CI without automated deployment.

### How to read the diagram

- **AWS Cloud box:** one AWS account. The **Region** box is the Singapore legal-entity stack (`sg`); every legal entity gets its own copy of this stack (NFR 12).
- **Global (edge):** Route 53, ACM, AWS WAF and CloudFront. These are global services, not tied to the region.
- **Security, operations and governance band:** regional services used by the whole stack (IAM, KMS, Secrets Manager, SSM Parameter Store, GuardDuty, CloudTrail, CloudWatch, SNS, ECR, AWS Backup).
- **Regional managed services boxes** (left and right of the VPC): see [What the "Regional managed services" boxes mean](#what-the-regional-managed-services-boxes-mean).
- **CRM VPC (10.0.0.0/16):** two Availability Zone boxes (ap-southeast-1a and 1b). Each has three subnet tiers:
  - **Public subnet:** the NAT gateway only.
  - **Private app subnet:** ECS tasks, the ingest Lambda's network interfaces, and the API Gateway VPC Link network interfaces.
  - **Isolated DB subnet:** RDS. AZ-1a holds each service's primary; AZ-1b holds its standby.
- **Orange dashed columns:** one ECS service each, spanning both AZs, with target-tracking auto scaling (min 1, max 6 tasks per service). The three x-factor columns are **alerts (XF1)**, **engagement (XF2)** and **health-monitor (XF3)**, with **dashboard (F7)** beside them. dashboard-service has no database, so its database slots are empty.
- **Engagement (F7) column:** engagement-service, deployed from its own `agent-engagement` stack into the same cluster. Its engagement-db is single-AZ even in the high-availability week, so it has no standby in AZ-1b ([ADR-0051](../adr/ADR-0051-engagement-service-and-mail-dispatcher.md)).
- **Scheduled tasks in AZ-1a:** the alert worker, the health analysis task and chain-verify sit in their owning service's column because each is built from that service's image. One EventBridge line feeds all three.
- **Tavily search API** sits outside AWS: a third-party news search that x-factor 1's worker calls over HTTPS through the NAT gateway, with public terms only. It replaced the planned Bedrock AgentCore web search in Tokyo, which an AWS Organization service control policy blocks ([ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md)).
- **chain-verify** sits in the auditor column because it is built from the auditor's code and container image. It is a separate scheduled task, not part of the auditor service ([Audit integrity and GDPR](../security/security-model.md#audit-integrity-and-gdpr)).
- **db-migrate** stands for each service's migration step: an init container in that service's task that migrates only its own database before the service starts ([ADR-0028](../adr/ADR-0028-cicd-migrations-terraform.md) decision 3, [Databases](../database/schema.md#databases)).
- **db-console** stands for one-off psql tasks, one per database, that operators start for the runbook's SQL steps. The note under the ECS columns records that every service task also runs a **tls-init** container that writes its TLS certificate, signed by the project CA, before the app starts.
- **Core banking system** is drawn outside AWS because it is an external system. Its EC2 hosting is only a simulation aid.
- **Agent and client mailboxes** and **data.gov.sg** are also drawn outside AWS: Amazon SES delivers Agent Engagement mail to the mailboxes, and engagement-service reads public holidays from data.gov.sg.
- **Gateway endpoints** for S3 and DynamoDB sit on the VPC edge, so traffic to those services stays off the NAT gateway.
- **GitHub Actions** is outside AWS and runs CI only: builds, tests, contract checks and security scans. Deployment is done by hand from the runbook, as an MFA-protected role, because automated deployment would need the infrastructure kept running, which costs too much ([ADR-0058](../adr/ADR-0058-every-deployment-ends-with-full-teardown.md)).

### What the "Regional managed services" boxes mean

The two boxes labelled Regional managed services hold AWS services that:

- **are regional:** they live in ap-southeast-1 and belong to this legal entity's stack, unlike the global edge services;
- **are fully managed by AWS:** there are no servers, patches or capacity to manage, and AWS runs them across several AZs by default;
- **sit outside the VPC:** ECS tasks reach them over HTTPS through the NAT gateway, or through a VPC endpoint where one exists (the S3 and DynamoDB gateway endpoints). Access is controlled by IAM policies, not security groups.

The split into two boxes is only for layout: services used by the browser and the entry path are on the left, and services used by the back end are on the right.

| Left box | What it does in this design |
|---|---|
| **Cognito user pool** | Signs users in (OAuth 2.0 authorization-code flow with PKCE), enforces MFA, stores password hashes, and puts the Admin/Agent group in the JWT. |
| **API Gateway HTTP API** | The one public API entry point. Validates the JWT and throttles traffic, then forwards through the VPC Link to the internal ALB. |
| **S3: frontend static files** | Private bucket holding the built frontend, served only through CloudFront (OAC). |
| **Cognito triggers (Lambda)** | One small function Cognito runs after a login and after a password-reset confirmation. It sends those events straight to the SQS audit queue; if that fails, the login still succeeds and the event is logged to CloudWatch for replay ([Authentication events](../security/security-model.md#authentication-events)). |
| **S3: ID documents** | Private, KMS-encrypted bucket for client identity documents (synthetic only). Uploads and views go through client-service, which encrypts each file with the client's data key; only client-service's role can reach the bucket ([ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md)). The stored copy is the audit record of what the agent checked. |
| **Amazon SES for client verification** (deferred) | Would send the client a verification request e-mail. Still deferred: agents verify NRIC documents manually ([ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md)). SES itself is used by Agent Engagement and is in the right box. |
| **EventBridge Scheduler** | Invokes the transaction-ingest Lambda about every 5 minutes, and starts chain-verify (daily), the alert worker (every 4 hours, from the `alert-worker` stack) and the health analysis task (daily, 08:00 SGT). |

| Right box | What it does in this design |
|---|---|
| **SQS audit queue** (standard, `audit-insert`) | One standard queue shared by every producer. Carries audit events to the auditor; order does not matter, and the auditor discards duplicates by `event_id`. |
| **SQS dead-letter queue** (`audit-insert-dlq`) | Keeps audit events that failed 5 times, for inspection and replay. |
| **S3 audit anchor (Object Lock)** | Stores the daily hash-chain head where it cannot be changed or deleted during the retention period. |
| **client-keys (DynamoDB)** | One item per client: the client UUID and that client's data key, stored only in its KMS-wrapped (encrypted) form. Used only by client-service. |
| **Amazon Bedrock (ap-southeast-1)** | Foundation models, called from Singapore through inference profiles: alert summaries written from public data only (x-factor 1, Nova 2 Lite, global profile), and plain-language wording of health-monitor findings and follow-up drafts (x-factor 3 and Feature 7, Nova Lite, Asia-Pacific profile). |
| **Amazon SES** (Agent Engagement) | Delivers Feature 5's mail (flow 14a): calendar invites, reminders and follow-ups the agent has approved. The sending identity comes from the `mail-identity` stack; sandbox recipients only for the demo ([ADR-0053](../adr/ADR-0053-email-delivery-through-ses.md)). |

## Components

Recorded in [ADR-0017](../adr/ADR-0017-microservices-per-business-area.md) (Accepted); the Cognito triggers' runtime and memory in [ADR-0040](../adr/ADR-0040-cognito-triggers-java-lambda.md) (Accepted).

| Component | Responsibility | Runtime |
|---|---|---|
| **Frontend** (S3 + CloudFront) | Presentation layer only; no business logic. | Static files |
| **API Gateway HTTP API** | Single public API entry point; Cognito JWT validation; stage throttling. | Managed |
| **Internal ALB** | Load balancing across tasks in both AZs; path routing to services; HTTPS. | Managed |
| **user-service** | F1: create, update and disable users, password reset, logout; `ROOT_ADMIN` application role and root-only Admin creation; Cognito admin API calls; activity feed (`GET /api/activities`: admins see all agents' activities, agents their own; changes only). | Java 25, ECS |
| **client-service** | F2: client profile CRUD, Appendix 2 validation, identity verification (manual: the agent may upload the NRIC, checks it and then verifies the client, naming the documents checked or recording that there were none; [ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md)), agent ownership rules. Encrypts the 11 personal client fields with a per-client data key; IDs, the owning agent, verification status and timestamps stay plaintext (envelope encryption, [Client data encryption (envelope encryption)](../security/security-model.md#client-data-encryption-envelope-encryption)). Currently implemented as `client-profile-management-service`; accounts have moved to account-service ([ADR-0017](../adr/ADR-0017-microservices-per-business-area.md)). Admins may reassign clients, with their accounts, to another enabled agent (`POST /api/clients/reassignments`, flow 15; [ADR-0060](../adr/ADR-0060-client-reassignment.md)); admins read every client but change none otherwise ([ADR-0059](../adr/ADR-0059-admins-read-only-on-client-data.md)). Serves Client Health each client's current agent on a private route (`/internal/v1/clients/{clientId}/assignment`). | Go, ECS |
| **account-service** | F2: bank account create, delete and status change (accounts open as Pending until the client is verified; [ADR-0050](../adr/ADR-0050-accounts-pending-until-client-verified.md)). Checks that the client exists and belongs to the agent by calling client-service, instead of a database foreign key. Also answers ownership checks for transaction-service, the account lookup for the ingest job, the account directory for event alerts ([ADR-0066](../adr/ADR-0066-crm-linked-current-alert-holdings.md)) and the account moves of a client reassignment. | Go, ECS |
| **transaction-service** | F3: transaction query API (`GET /api/accounts/{accountId}/transactions`, and `GET /api/transactions` for admins). Checks the caller's Cognito token and reads an account's transactions only after account-service confirms the caller is an admin or owns the account; the dashboard's totals are scoped the same way, and reads are audited through its own `read_audit_outbox` ([ADR-0064](../adr/ADR-0064-transaction-service-authenticates-its-own-reads.md)). Serves Client Health's matched history on a private route ([ADR-0065](../adr/ADR-0065-client-health-matched-transaction-history.md)). The same Go codebase as transaction-ingest (`services/sftp/receiver`). | Go, ECS |
| **transaction-ingest** | F3: ingestion worker, built from the same Go codebase as transaction-service (`services/sftp/receiver`). Connects to the external SFTP server over SSH, reads ready files, validates and imports them, and moves each file to `processed/` or `rejected/`. | Go, AWS Lambda (VPC-attached), invoked by EventBridge Scheduler |
| **event-alert-service** (x-factor 1) | Market-news alerts. `api` mode serves `/api/alerts` to agents: list and view alerts, record a decision (`INFORM_CLIENT`, `MONITOR` or `DISMISS`) and give feedback; admins can read but not decide. The `worker` task, started every 4 hours, selects widely held instruments, searches public news through the Tavily API with public terms, has Nova 2 Lite classify each item and write a shared summary from public data, matches and scores, and routes alerts to assigned agents, who see one briefing per event with its affected clients. Agents record each account's securities positions through `/api/alert-portfolios` ([ADR-0066](../adr/ADR-0066-crm-linked-current-alert-holdings.md)). The worker is deployed from its own `alert-worker` stack ([ADR-0048](../adr/ADR-0048-alert-worker-terraform-stack.md)). | Java 25, ECS service + scheduled ECS task |
| **dashboard-service** (Feature 7, additional) | Business dashboard for admins (bank-wide, per agent) and agents (own book). Stateless: fans out to aggregate endpoints on user-, client-, account- and transaction-service with the caller's JWT ([ADR-0039](../adr/ADR-0039-dashboard-aggregate-endpoints.md), [ADR-0064](../adr/ADR-0064-transaction-service-authenticates-its-own-reads.md)); 3-minute per-caller cache. | Java 25, ECS |
| **client-health-monitor-service** (x-factor 3) | Task API for relationship follow-up tasks. A daily scheduled analysis task (08:00 SGT) runs the deterministic rules engine (recurring-credit interruption, activity decline, sustained net outflow) and uses Bedrock (Nova Lite, Asia-Pacific profile) only for wording, with a template fallback. The task API has anonymous liveness and readiness probes, and both tasks publish identifier-free metrics to CloudWatch. | Java 25, ECS service + scheduled ECS task |
| **engagement-service** (x-factor 2, Feature 5) | Agent Engagement (`/api/engagement/*`): calendar events, optionally linked to the agent's own clients; e-mailed iCalendar invites; up to two reminder e-mails per event; Singapore public holidays; AI follow-up drafts, including check-ins from a Client Health task and follow-ups from an alert ([ADR-0068](../adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md)), which the agent edits and sends; notification preferences. A mail dispatcher in every task claims queued mail with a lease, so delivery is at least once and two tasks never claim the same row; nothing is sent while the stack is stopped. Deployed from its own Terraform stack, `agent-engagement` ([ADR-0051](../adr/ADR-0051-engagement-service-and-mail-dispatcher.md) to [ADR-0057](../adr/ADR-0057-holiday-sync-from-data-gov-sg.md)). | Java 25, ECS |
| **auditor** | Consumes audit events from SQS, appends them to the hash chain, serves audit reads over internal gRPC, and serves tamper findings to admins over a small internal REST API (`/api/corrupted-logs`), reached through user-service. | Go, ECS |
| **chain-verify** | Daily hash-chain verification; writes the chain head to S3 Object Lock. Built from the auditor codebase and image. | Go, ECS scheduled task |
| **cognito-triggers** | F1: sends each login and forgotten-password reset to the audit queue within 3 seconds, and never fails the sign-in; a failed send is logged for replay ([Authentication events](../security/security-model.md#authentication-events)). | Java 25, AWS Lambda (outside the VPC), invoked by Cognito |
| **db-migrate** | Applies one service's versioned schema migrations to that service's database before the service starts, then gives the service its own login without schema rights. Flyway, in an init container in the task of each service with a database. user-service and event-alert-service run with their own startup Flyway off in AWS; locally they still migrate on startup. | ECS init container |
| **db-console** | One-off psql task per database, started by operators for the runbook's SQL steps. Its security group is allowed on each database's port; no service uses it. | ECS task |
| **RDS for PostgreSQL** (users-db, clients-db, accounts-db, transactions-db, audit-db) | Relational ACID storage. One instance per service, each with a Multi-AZ standby. | Managed |
| **alerts-db, health-db** | The x-factor services' own RDS instances: holdings, instrument master, events, alerts and decisions (alerts-db); activity projection, signals, evidence, tasks and explanations (health-db). Each has its own outbox. | Managed |
| **engagement-db** | engagement-service's own RDS instance (`db.t4g.micro`), single-AZ even in the high-availability week: events, the mail queue, drafts stored with placeholders instead of names, holidays, preferences and its outbox. Resolved e-mail bodies and recipient addresses are not stored, and a draft's text is cleared once it is sent or discarded ([ADR-0068](../adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md)). | Managed |
| **Client key store** (DynamoDB `client-keys`) | Per-client data keys, one-to-one with client UUIDs, wrapped by a symmetric KMS key. Owned by client-service. | Managed (on-demand) |
| **SQS standard queue + dead-letter queue** | Durable, at-least-once delivery of audit events through one queue shared by all producers (`audit-insert`, with `audit-insert-dlq`). The auditor removes duplicates by `event_id`. | Managed |
| **Cognito** | OAuth 2.0 / OIDC identity provider, MFA, password storage. | Managed |
| **Core banking SFTP server** (external) | Stands in for the core banking system. A file generator writes synthetic transaction files and marks them ready; faults can be simulated. Outside the CRM architecture. | OpenSSH on an EC2 instance, in its own stack |

## Why each service on the diagram is included

Each service on the diagram is listed with the requirement it serves. "Spec" means the CS301 project specification v18.0. The **Optional?** column says what happens if the service is removed, so the team can make informed cost trade-offs.

### Edge (global)

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **CloudFront** | Serves the frontend and forwards `/api/*` to API Gateway from one HTTPS origin, so the frontend needs no CORS setup. Caches static files at the edge. | NFR 1 (caching), NFR 8 (HTTPS), NFR 11 (separate presentation layer) | No |
| **AWS WAF** | Web application firewall on CloudFront, using AWS managed rule groups (common exploits, bad inputs, SQL injection ([ADR-0073](../adr/ADR-0073-waf-sql-injection-rule-group.md)), IP reputation). | NFR 8 names "firewalls" | Can be left out of staging (about USD 10/month); keep in prod |
| **ACM** | Free TLS certificates: one in us-east-1 for CloudFront (a CloudFront requirement) and one for the internal ALB. | NFR 8, Feature 2 (encryption in transit) | Only with the custom domain ([open item 2](open-items.md#2-domain-name), resolved): without it, the ALB listens on HTTP inside the VPC |
| **Route 53** | DNS for the custom domain, which is registered by hand in Route 53: points the app's name at CloudFront and the internal ALB's name at the ALB, and holds the ACM validation records ([ADR-0042](../adr/ADR-0042-terraform-deployment.md) decision 8). | Needed for ACM certificate validation | The stack still runs without a domain, on the default `*.cloudfront.net` address |

### Identity and API entry (regional)

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **Cognito user pool** | Managed OAuth 2.0 / OIDC identity provider. Handles MFA, password hashing and storage, and Admin/Agent groups. user-service is already built on it. | Feature 1: OAuth 2.0, MFA, secure password storage | No |
| **Cognito triggers (Lambda)** | Login and user-initiated password-reset confirmation happen inside Cognito, not in our services. The `PostAuthentication` and `PostConfirmation_ConfirmForgotPassword` triggers are the only way to capture those events for the audit log. | Feature 1 audit: "login ... password changes" | No, unless login auditing is dropped |
| **API Gateway HTTP API** | Single public API entry point. Checks the Cognito JWT before any request reaches the VPC, and applies throttling. | NFR 8 (Zero Trust), NFR 5 (overload protection) | No |
| **API Gateway VPC Link ENIs** (both AZs) | Network interfaces that let API Gateway reach the internal ALB without exposing it to the internet. One per AZ, so the entry path survives the loss of an AZ. Its security group is the only way in from outside the VPC; inside it, the ALB also accepts the callers of its private routes: the Client Health tasks, event-alert-service (API and worker), engagement-service and the ingest Lambda ([ADR-0019](../adr/ADR-0019-edge-entry-chain.md) decision 4). No extra charge. | NFR 2, NFR 8 | No (needed for a private ALB) |

### Network

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **VPC with three subnet tiers** | Public (NAT only), private (application) and isolated (database) subnets in two AZs. Nothing except the NAT gateways has a public IP. | NFR 2 (multi-AZ), NFR 8 (multi-layer security) | No |
| **Internet gateway** | Required for the NAT gateways to reach the internet. | Supporting | No |
| **NAT gateway** (one per AZ) | Outbound internet access for private tasks: Cognito signing keys, SQS, Secrets Manager, ECR and other AWS APIs, and the SFTP connection to the external core banking system. Its fixed Elastic IP is what the SFTP server allows in. | Supporting; one per AZ for NFR 2 | Second NAT runs only in the high-availability demo week ([Environments and cost (target architecture)](../cost/budget.md)) |
| **S3 gateway endpoint** | Free. Keeps S3 traffic (including ECR image layers) off the NAT, which saves NAT data charges. | Cost | Yes, but it is free |
| **DynamoDB gateway endpoint** | Free. client-service reaches the client-keys table without going through the NAT, and the table's policy can require requests to come through this endpoint. | Cost, NFR 8 | Yes, but it is free |
| **Internal ALB** | Spreads requests across tasks in both AZs, runs health checks, and routes by path to each service. | NFR 1 says "use load balancers" | No |

### Compute

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **ECS Fargate services** (user, client, account, transaction, event-alert, dashboard, health-monitor, engagement, auditor) | Runs the containerised services with no servers to manage. Target-tracking auto scaling keeps at least two tasks per service, spread across the two AZs, and scales out to six ([ADR-0075](../adr/ADR-0075-two-tasks-minimum-per-service.md)). | NFR 1 (auto-scaling), NFR 2, NFR 11 (cloud native, containers) | No |
| **AWS Lambda: transaction-ingest** (VPC-attached) | The Feature 3 ingestion worker. Invoked on a schedule, it connects to the external SFTP server, imports ready files into transactions-db and audits each file. Costs almost nothing between runs ([Transaction ingestion (Feature 3)](data-flow.md#transaction-ingestion-feature-3)). | Feature 3 | No |
| **chain-verify** (scheduled ECS task) | Daily hash-chain verification and anchoring. Runs to completion and costs nothing between runs. | Spec: "Audit logs should be tamper-resistant" | No |
| **db-migrate** (init container in each service's task) | Applies each service's database migrations before its app starts. Only this container gets the database's master credentials; the app connects with its own login, which cannot change the schema. | NFR 3, NFR 10 (automated deployment) | Yes: migrations can run on service startup instead ([Databases](../database/schema.md#databases)) |
| **EventBridge Scheduler** | Invokes the transaction-ingest Lambda every few minutes and starts chain-verify, the alert worker and the health analysis task, with no always-on scheduler process. | Feature 3, audit integrity, Features 4–6 | No |
| **Alert worker, health analysis** (scheduled ECS tasks) | Batch work for x-factors 1 and 3. Runs once per schedule and costs nothing between runs; a timer inside the always-on services would run in every copy and restart with every deployment. | Features 4–6, NFR 1 | No |
| **ECR** | Private container image registry, with vulnerability scanning on push. | NFR 8 (security audits), NFR 10 (CI/CD) | No |
| **db-console** (one-off ECS task per database) | Gives operators a psql session inside the VPC for the runbook's one-time SQL, with no bastion host or public database. | NFR 8 (no public databases), operations | Yes: used only for manual steps |
| **ECS Fargate: engagement-service** (x-factor 2) | Runs Agent Engagement's API and its mail dispatcher in one service, so reminders need no separate worker or schedule. One 1 vCPU / 2 GB task while running, at most 2. | Feature 5 (x-factor 2) | Yes: an x-factor in its own stack, which can stay undeployed |

### Data and storage

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **RDS for PostgreSQL, one instance per service** (primary) | Relational ACID database. Each microservice owns its own instance, so no service can read another's data, and each can be scaled, maintained and fail independently. | Feature 2 ("relational database ... ACID"), NFR 3 and 11, NFR 4 | No |
| **RDS Multi-AZ standby** (one per database) | A synchronously replicated copy in the other AZ. RDS fails over to it automatically if the primary or its AZ fails. | NFR 2 (redundancy, failover, multiple AZs), NFR 9 | Run only in the high-availability demo week ([Environments and cost (target architecture)](../cost/budget.md)) |
| **S3: frontend static files** | Stores the frontend's built files, private behind CloudFront. | NFR 11 (separate presentation layer) | No |
| **S3: ID documents** | Private, KMS-encrypted storage for identity documents (synthetic NRIC only) uploaded during client verification, through client-service and encrypted with the client's data key ([ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md)). The agent checks each document manually; the stored copy is the audit evidence of what was checked. | Feature 2 "Verify Client Identity" | No |
| **RDS: alerts-db, health-db** | Own databases for the two x-factor services that store data, so neither reads another service's tables. Same instance size and Multi-AZ pattern as the other databases. | NFR 3 and 11 (service boundaries), Features 4–6 | Could share one instance with a database each, saving about USD 13–15/month, at the cost of isolation |
| **RDS: engagement-db** | engagement-service's own database, so no other service reads its events, mail queue or drafts. `db.t4g.micro`, single-AZ even in the demonstration week ([ADR-0051](../adr/ADR-0051-engagement-service-and-mail-dispatcher.md) decision 1). | NFR 3 and 11 (service boundaries), Feature 7 | Could move to the shared x-factor fallback instance |
| **DynamoDB: client-keys** | Holds each client's data key, KMS-wrapped, apart from clients-db, so a leaked clients-db snapshot is useless on its own. Key-value lookups by client UUID; regional and multi-AZ by default, with point-in-time recovery. On-demand cost is well under USD 1/month at this scale. | Feature 2 (encryption at rest), NFR 8 (GDPR) | No, while client data is encrypted per client |
| **S3 audit anchor (Object Lock)** | Stores the daily hash-chain head so nobody, including an admin with database access, can silently rewrite audit history. | Spec: "Audit logs should be tamper-resistant" | Could be dropped, which weakens the tamper-resistance argument |

### Audit pipeline

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **SQS audit queue** (standard, shared by all producers) | Carries audit events from every service to the auditor, so business actions never wait on, or fail because of, the auditor ([Audit write path: SQS, not gRPC](data-flow.md#audit-write-path-sqs-not-grpc)). | Audit requirements for Features 1–6; NFR 2, 4, 5, 9 | No |
| **SQS dead-letter queue** | Holds events the auditor cannot process after 5 attempts, for inspection and replay instead of losing them. | NFR 5 (graceful failure), NFR 9 (no data loss) | No |

### AI and external search

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **Amazon Bedrock** (ap-southeast-1) | Foundation models, billed per use, called from Singapore. x-factor 1's alert summaries from public news use Nova 2 Lite through its global profile, so AWS may process them in any Region the profile covers ([ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md)). x-factor 3's wording of verified health-monitor facts, and x-factor 2's follow-up drafts, use Nova Lite through its Asia-Pacific profile, because those inputs come from client records ([ADR-0067](../adr/ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md)). No model the project account can invoke runs in-Region in Singapore. Model invocation has a kill switch and its own budget alert. | Features 4–6 (at least one must use AI) | No; x-factor 3 still works on templates if Bedrock is off |
| **Tavily search API** (third party, outside AWS) | Public news search for x-factor 1, restricted to approved domains, a date range and 10 results a query; its API key is in Secrets Manager. Chosen because an AWS Organization service control policy blocks Bedrock AgentCore web search (Tokyo) and Bedrock's own web search in the project account ([ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md)). | Feature 4 (market-news alerts) | Yes, but x-factor 1 then has no news source |

### Security, operations and governance

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **IAM** | Least-privilege role per ECS task and Lambda; an MFA-protected deploy role (`crm-infra-admin`, 4-hour sessions) for the manual deployments ([ADR-0046](../adr/ADR-0046-human-deploy-access-mfa-role.md)); only producer roles may send to the audit queue. An OIDC role for GitHub Actions is defined but off by default, since deployment is not automated. | NFR 8 (Zero Trust), NFR 10 | No (free) |
| **KMS** | Encryption keys for RDS, S3, SQS, DynamoDB and backups. A symmetric customer-managed key wraps every per-client data key (envelope encryption): client-service calls `GenerateDataKey` for a new client and `Decrypt` to unwrap a stored key. | Feature 2 (encryption at rest), NFR 8 (GDPR) | No (about USD 1 per key per month) |
| **Secrets Manager** | Database credentials (RDS can manage and rotate each instance's master password here), the ingestion worker's SSH private key, the machine clients' Cognito secrets, the service TLS keys and the alert worker's Tavily API key. | NFR 8 (secure secret storage) | Could move to SSM SecureStrings to save about USD 0.40 per secret, losing managed rotation |
| **SSM Parameter Store** | Free storage for non-secret configuration, including a record of the SFTP server's host-key fingerprint. The ingest Lambda itself receives the fingerprint as an environment variable ([Transaction ingestion (Feature 3)](data-flow.md#transaction-ingestion-feature-3)). | NFR 3, NFR 8 | Yes, if everything moves to Secrets Manager |
| **GuardDuty** | Managed threat and intrusion detection, using CloudTrail, VPC flow and DNS logs. The account's existing detector is used and is managed outside Terraform: GuardDuty allows one detector per account per Region, so the stacks neither create nor destroy it. | NFR 8 names "intrusion detection" | No; it is account-level and survives every teardown |
| **CloudTrail** | Records every AWS API call in the account: who changed which resource, and when. One trail with log-file validation, from the `bootstrap` stack. | NFR 8 (security audits), NFR 7 | No (one management-events trail is free) |
| **CloudWatch** | Logs, metrics, a dashboard and alarms for services, queues, databases and ingestion. | NFR 7 (monitoring, dashboards, alerting) | No |
| **SNS** | Sends CloudWatch alarms and budget alerts to the team by e-mail. | NFR 7 ("alerting mechanisms") | No (effectively free) |
| **AWS Backup** (daily plan) | One tag-based daily backup plan covering the RDS databases and the client-keys table, kept for 7 days in a Singapore vault; the design adds a copy to a vault in ap-southeast-2 ([Parts of the architecture not shown on the diagram](#parts-of-the-architecture-not-shown-on-the-diagram) and [Why AWS Backup is included when RDS already has backups](../database/schema.md#why-aws-backup-is-included-when-rds-already-has-backups)). The copy is not deployed, and may never be ([Limits of the team's AWS account](#limits-of-the-teams-aws-account)). | NFR 2, NFR 5, NFR 9 | See [Why AWS Backup is included when RDS already has backups](../database/schema.md#why-aws-backup-is-included-when-rds-already-has-backups) |

### External and delivery

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **Core banking SFTP server** (external, EC2) | Simulates the core banking system that sends the CRM its transactions. Drawn outside AWS because it is not part of the CRM. Runs as self-hosted OpenSSH on a `t4g.micro` EC2 instance in its own stack, not AWS Transfer Family, which costs about USD 216/month even when idle. | Feature 3: "Host an external server" | No |
| **Amazon SES for client verification** (deferred) | Would send the client an identity-verification request e-mail. Deferred: agents verify NRIC documents manually ([ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md)). | Feature 2: "System sends a verification request to the client" ([open item 19](open-items.md#19-client-verification-request-feature-2)) | Yes: not used until verification is automated |
| **Amazon SES** (Agent Engagement) | Sends Feature 5's mail: invites, cancellations and reminders to agents, and follow-up e-mails the agent has approved to clients (with the agent in Bcc), through a configuration set that requires TLS and logs deliveries, bounces and complaints. The identity is a `mail.sg.<domain>` domain with DKIM, MAIL FROM and DMARC, or one verified sender address in an account without the domain's hosted zone; sandbox recipients only for the demo ([ADR-0053](../adr/ADR-0053-email-delivery-through-ses.md), [ADR-0055](../adr/ADR-0055-sender-address-without-a-domain.md)). | Feature 5 | No, while Feature 5 is deployed |
| **data.gov.sg** (external) | MOM's consolidated Singapore public holidays dataset, read at start-up and daily to add newly published years ([ADR-0057](../adr/ADR-0057-holiday-sync-from-data-gov-sg.md)). | Feature 5 (holiday-aware calendar and drafts) | Yes: the migration-seeded table still covers 2026–2027 |
| **Agent and client mailboxes** (external) | Gmail or Outlook mailboxes that receive invites, reminders and follow-ups; the agent's calendar adds, moves or removes the meeting from the invite. No OAuth or calendar API is used ([ADR-0052](../adr/ADR-0052-calendar-invites-by-email.md)). | Feature 5 | No |
| **GitHub Actions** (outside AWS) | CI on every pull request and push: Java and Go builds and tests, gRPC and OpenAPI contract checks, CodeQL, OSV-Scanner, and frontend lint, tests and build. Deployment is not automated: it would need the infrastructure kept up between changes, which costs too much, so each deployment is run by hand from the runbook and ends with a full teardown ([ADR-0058](../adr/ADR-0058-every-deployment-ends-with-full-teardown.md)). | NFR 10 ("demo a CI/CD"), NFR 13 | No |

## Parts of the architecture not shown on the diagram

These are left off the diagram to avoid overcrowding. They are still part of the target architecture and are built with it.

| Item | What it does | How it connects to the diagram |
|---|---|---|
| **ECS Service Connect and its Cloud Map namespace** | A private service registry plus an Envoy proxy sidecar in each task. Services call each other by name (for example `auditor:50051`); the proxy picks a healthy task in either AZ, balances each gRPC request, retries and ejects failing tasks, and reports metrics. No extra charge beyond the sidecar's CPU and memory. | Carries flow 8 (gRPC audit reads). Also carries service-to-service API checks that are not drawn: account-service checking a client ID with client-service, transaction-service checking account ownership with account-service before a transaction read ([ADR-0064](../adr/ADR-0064-transaction-service-authenticates-its-own-reads.md)), engagement-service checking client ownership with client-service, and dashboard-service's fan-out (flow 12). Only ECS **services** can use it: the transaction-ingest Lambda's account lookup and the standalone health analysis task call through the internal ALB ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)), and the services that reach another service's private route over HTTPS (Client Health, event alerts, engagement-service) use the ALB too. Supports NFR 1 and 2 on the audit read path; needed while gRPC reads are used. |
| **AWS Backup vault in ap-southeast-2 (DR region)** | Receives a cross-region copy of each daily database snapshot. Backup Vault Lock stops anyone deleting or shortening the retention of recovery points. | The diagram shows only the AWS Backup daily plan in the security band. The vault is used to rebuild the stack in ap-southeast-2 if Singapore is unavailable ([Why AWS Backup is included when RDS already has backups](../database/schema.md#why-aws-backup-is-included-when-rds-already-has-backups)). Not deployed, and may never be ([Limits of the team's AWS account](#limits-of-the-teams-aws-account)). |
| **AWS Budgets** | Alerts at 10, 20, 40, 60, 80 and 100% of USD 200, which keeps the USD 100 contingency outside the USD 300 limit, plus a separate monthly alert for Bedrock spend; made by hand in the Billing console ([ADR-0058](../adr/ADR-0058-every-deployment-ends-with-full-teardown.md) decision 7). Required by the course budget rule (project_plan §23). | Sends alerts through SNS (shown in the security band). |
| **Security groups** | One per service, per database, for the ALB, the VPC Link and the ingest Lambda. They implement the allowed-traffic chain in [Identity and security](../security/security-model.md#identity-and-security). | Apply to every icon inside the VPC; not drawn as icons. |
| **tls-init container and project CA** | An init container in every service task writes that service's TLS certificate, signed by a project CA that Terraform generates, before the app starts, so traffic between the ALB, Service Connect and the tasks is encrypted without AWS Private CA. | Shown only as the note under the ECS columns. |
| **Separate Terraform stacks** | dashboard-service (`stacks/business-dashboard`, [ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md)), the alert worker (`stacks/alert-worker`, [ADR-0048](../adr/ADR-0048-alert-worker-terraform-stack.md)) and engagement-service with engagement-db (`stacks/agent-engagement`) are deployed from their own stacks into the CRM's network and cluster; `stacks/mail-identity` holds the SES identity, and `stacks/sftp-server` the simulated core banking server. | Their columns sit in the same ECS cluster on the diagram. |
| **Outbox relay** | Not a separate component: a loop inside each service that reads unsent rows from the service's outbox table and sends them to SQS. The ingest job has no long-running relay loop, so it relays its `audit_outbox` rows at the end of each run ([Transaction ingestion (Feature 3)](data-flow.md#transaction-ingestion-feature-3)). | Part of flow 6; the outbox table lives in each service's own database. |

## Limits of the team's AWS account

The team deploys to an AWS account whose service control policy refuses two parts of this design. The policy is set by the organisation that manages the account, not by the team, and it may never be lifted, so the deployed system may never include them. [ADR-0042](../adr/ADR-0042-terraform-deployment.md) decision 11 leaves them out of the Terraform, keeping their code commented out to restore if the policy changes.

| Part | Refused action | What the deployed system lacks |
|---|---|---|
| Cross-region backup copy: the AWS Backup vault in ap-southeast-2, its KMS key and its Vault Lock | `backup:CreateBackupVault` in ap-southeast-2 | Backups stay in ap-southeast-1, with no Vault Lock. Losing the Region loses the backups too, so recovery from a regional outage is not supported ([ADR-0023 deployment status](../adr/ADR-0023-rds-per-service-backup-no-cache.md#deployment-status)) |
| WAF request logging to CloudWatch Logs | `logs:CreateLogGroup` in us-east-1, where CloudFront's WAF and its log group must be | No record of which requests WAF blocked, or why. WAF still filters requests, and its CloudWatch metrics and sampled requests remain |

The rest of this page describes the target architecture, including both parts.

## Network layout

Recorded in [ADR-0019](../adr/ADR-0019-edge-entry-chain.md) (Accepted).

- The VPC (`10.0.0.0/16`) has three subnet tiers in each AZ: public (`10.0.0.0/24`, `10.0.1.0/24`: NAT only), private app (`10.0.10.0/24`, `10.0.11.0/24`) and isolated DB (`10.0.20.0/24`, `10.0.21.0/24`). Nothing in the private or isolated tiers is reachable from the internet.
- Each service's RDS primary is in AZ-1a's isolated subnet and its Multi-AZ standby in AZ-1b's. After a failover the roles swap; the endpoint name stays the same.
- Each database instance has its own security group that allows port 5432 only from its owning service's security group.
- API Gateway enters the VPC through VPC Link network interfaces in both AZs' private app subnets; the ALB accepts traffic only from the VPC Link's security group and from the callers of its private routes: the Client Health tasks (the analysis task cannot use Service Connect, and the task API shares its security group), event-alert-service's API and worker (the CRM account directory), engagement-service (Client Health tasks and alerts for follow-ups) and the ingest Lambda.
- There is one NAT gateway per AZ in the high-availability week and one otherwise; the NAT gateways are removed while the stack is stopped, keeping their Elastic IPs.
- The external SFTP server is outside the CRM network. The ingestion Lambda reaches it outbound through the NAT gateway and internet gateway, and the server accepts connections only from the NAT gateway's Elastic IP.
- The free S3 and DynamoDB gateway endpoints keep traffic to those services off the NAT.

## Why ECS Fargate (and not EKS)

Recorded in [ADR-0018](../adr/ADR-0018-ecs-fargate-compute.md) (Accepted).

- **Cost:** an EKS cluster's control plane alone costs about USD 73/month before any worker nodes. ECS has no control-plane charge.
- **Fit:** the spec's "cloud native, containerisation, orchestration" principle is met by containers orchestrated by ECS. Kubernetes is given in the spec as an example, not a requirement.
- **Effort:** Fargate removes node management, so the team can explain and operate the whole platform within the course timeline.

## Relationship to existing documents

- [`nfr-notes.md`](nfr-notes.md) predates this target architecture. It keeps the same "one RDS instance per service" decision, but differs in several places. Once the ADRs listed in the [ADR index](../adr/README.md) are accepted, `nfr-notes.md` should be marked superseded where they differ:
  - **Edge:** `nfr-notes.md` describes an API Gateway private integration that resolves services through Cloud Map; this design uses API Gateway → VPC Link → internal ALB ([ADR-0019](../adr/ADR-0019-edge-entry-chain.md)).
  - **Resiliency:** `nfr-notes.md` says no retries or circuit breakers are applied; this design uses timeouts, retries with backoff and circuit breakers on outbound calls (gRPC audit reads, the SFTP connection).
  - **Region:** `nfr-notes.md` is single-region with no cross-region recovery; this design adds an AWS Backup cross-region copy to ap-southeast-2 ([ADR-0023](../adr/ADR-0023-rds-per-service-backup-no-cache.md)). Until the account allows that copy, the deployed system is single-region too ([Limits of the team's AWS account](#limits-of-the-teams-aws-account)).
  - **Data segregation:** `nfr-notes.md` assumes a single legal entity; this design deploys one stack per legal entity ([ADR-0026](../adr/ADR-0026-one-stack-per-legal-entity.md)).
  - **WAF:** `nfr-notes.md` says the system has no WAF; this design puts AWS WAF on CloudFront.
  - **Message broker:** `nfr-notes.md` already records that the auditor reads from SQS ([ADR-0004](../adr/ADR-0004-auditor-kafka-to-sqs-migration.md)); this design extends that with a per-service outbox and one shared queue ([ADR-0020](../adr/ADR-0020-audit-write-path-outbox-shared-sqs.md)).
- Accepted ADRs it builds on: [ADR-0004](../adr/ADR-0004-auditor-kafka-to-sqs-migration.md) (auditor on SQS) and [ADR-0005](../adr/ADR-0005-cognito-security-across-features-1-and-2.md) (shared Cognito security). The new ADRs extend them; they do not supersede them.
- The event-driven alerts feature's own ADRs (ADR-0004 to ADR-0016 on that feature line) are merged into `docs/adr/` with their original numbers, so ADR-0004, ADR-0005 and ADR-0006 each name two records; the [ADR index](../adr/README.md) explains this under "Numbering note". Where they differ from this design, see [open item 4](open-items.md#4-reconcile-the-x-factor-branch-adrs-with-the-target-architecture). The first business-dashboard branch's ADR-0001, ADR-0003 and ADR-0004 were not carried over: [ADR-0033](../adr/ADR-0033-xf2-dashboard-topology-cache-audit.md) replaces them, and its ADR-0002 became [ADR-0038](../adr/ADR-0038-dashboard-metric-definitions.md).
