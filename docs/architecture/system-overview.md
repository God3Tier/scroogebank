# Target architecture: system overview

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-01.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Each decision is recorded as a Proposed ADR in the [ADR index](../adr/README.md).

This page is the entry point to the CRM's target AWS architecture. It replaces the team's `ARCHITECTURE.docx` as the reviewable source in the repository.

## Document map

| Page | Contents |
|---|---|
| This page | Summary, how to read the diagram, components, why each AWS service is included, parts not on the diagram, network, compute choice |
| [Request, audit and ingestion flows](data-flow.md) | Numbered flows 1–13a, audit write path (outbox + SQS), audit read path (gRPC), transaction ingestion (SFTP + Lambda) |
| [Security model](../security/security-model.md) | Identity, authorization, perimeter, encryption, audit tamper resistance, GDPR erasure, authentication events, client data envelope encryption |
| [Database design](../database/schema.md) | Databases and owners, one RDS instance per service, AWS Backup |
| [Schema migrations](../database/migrations.md) | The db-migrate task per service |
| [X-factor features](x-factors.md) | Market-news alerts, business dashboard, Client Relationship Health Monitor |
| [NFR mapping](nfr-mapping.md) | How each non-functional requirement (NFR 1–13) is met |
| [Test strategy](../testing/test-strategy.md) | How the design will be evaluated |
| [Environments and cost](../cost/budget.md) | dev, staging and prod; rough monthly cost; budget levers |
| [Decision log](decision-log.md) | Every major decision: chosen, not chosen and why, with its ADR |
| [Open items](open-items.md) | Decisions still needed from the team, with owners |
| [ADR index](../adr/README.md) | ADR-0017 to ADR-0034 record these decisions (Proposed) |

## Summary

- **Region and topology:** one AWS region (`ap-southeast-1`, Singapore), two Availability Zones, and one full stack per legal entity.
- **Frontend:** static frontend files in a private S3 bucket, served by CloudFront with AWS WAF in front.
- **API entry:** all `/api/*` traffic goes through API Gateway (which validates the Cognito JWT and applies throttling), then through a VPC Link to an internal Application Load Balancer.
- **Business services:** containers on **ECS Fargate**, one microservice per business area: user-service, client-service, account-service, transaction-service, the three x-factor services and the auditor. Each runs at least two tasks spread across both AZs.
- **X-factors (Features 4–6):** event-driven market-news alerts, a business dashboard, and a Client Relationship Health Monitor ([x-factor features (Features 4–6)](x-factors.md)). Only public search terms leave Singapore, for a web search in Tokyo; all client data and AI summaries stay in Singapore.
- **Databases:** each service has its **own RDS for PostgreSQL instance**, with a **Multi-AZ standby** in the second AZ. This balances isolation against cost ([Databases](../database/schema.md#databases)). Client personal data is encrypted field by field with a per-client key, and those keys are kept in a separate **DynamoDB** table, wrapped by a symmetric **KMS** key ([Client data encryption (envelope encryption)](../security/security-model.md#client-data-encryption-envelope-encryption)).
- **Auditor:** an **internal-only** service; the frontend never calls it.
  - **Writes** reach it asynchronously through one shared **Amazon SQS standard** queue (`audit-insert`). Services with a database use a transactional outbox; the Cognito triggers and dashboard-service, which have none, send directly.
  - **Reads** use synchronous **gRPC** calls from other services, through **ECS Service Connect**.
  - A daily **chain-verify** task checks the hash chain and anchors its head in an **S3 Object Lock** bucket.
- **Transaction import:** a scheduled **Lambda function** connects out over SSH/SFTP to the external core banking SFTP server (simulated on an EC2 instance), reads files marked as ready, and imports them.
- **Identity:** **Amazon Cognito** handles sign-in, with mandatory MFA and Admin/Agent groups.

## Architecture diagram

The diagram is kept outside the repository, in the team's Google Drive: `(NEW) AWS Architecture Diagram.drawio` (full version, with the numbered-flows legend) and `(NEW) AWS Architecture Diagram - Compact.drawio`. Its numbered arrows match the [numbered flows](data-flow.md#numbered-flows).

### How to read the diagram

- **AWS Cloud box:** one AWS account. The **Region** box is the Singapore legal-entity stack (`sg`); every legal entity gets its own copy of this stack (NFR 12).
- **Global (edge):** Route 53, ACM, AWS WAF and CloudFront. These are global services, not tied to the region.
- **Security, operations and governance band:** regional services used by the whole stack (IAM, KMS, Secrets Manager, SSM Parameter Store, GuardDuty, CloudTrail, CloudWatch, SNS, ECR, AWS Backup).
- **Regional managed services boxes** (left and right of the VPC): see [What the "Regional managed services" boxes mean](#what-the-regional-managed-services-boxes-mean).
- **CRM VPC (10.0.0.0/16):** two Availability Zone boxes (ap-southeast-1a and 1b). Each has three subnet tiers:
  - **Public subnet:** the NAT gateway only.
  - **Private app subnet:** ECS tasks, the ingest Lambda's network interfaces, and the API Gateway VPC Link network interfaces.
  - **Isolated DB subnet:** RDS. AZ-1a holds each service's primary; AZ-1b holds its standby.
- **Orange dashed columns:** one ECS service each, spanning both AZs, with target-tracking auto scaling (min 2, max 6 tasks per service). The three x-factor columns are **alerts (XF1)**, **dashboard (XF2)** and **health-monitor (XF3)**. dashboard-service has no database, so its database slots are empty.
- **Scheduled tasks in AZ-1a:** the alert worker, the health analysis task and chain-verify sit in their owning service's column because each is built from that service's image. One EventBridge line feeds all three.
- **Region ap-northeast-1 (Tokyo)** sits outside the Singapore region. It holds only the Bedrock AgentCore web search used by x-factor 1, which receives public terms only.
- **chain-verify** sits in the auditor column because it is built from the auditor's code and container image. It is a separate scheduled task, not part of the auditor service ([Audit integrity and GDPR](../security/security-model.md#audit-integrity-and-gdpr)).
- **db-migrate** stands for one migration task per service, each touching only its own database ([Databases](../database/schema.md#databases)).
- **Core banking system** is drawn outside AWS because it is an external system. Its EC2 hosting is only a simulation aid.
- **Gateway endpoints** for S3 and DynamoDB sit on the VPC edge, so traffic to those services stays off the NAT gateway.
- **GitHub Actions** is outside AWS; it deploys through OIDC.

### What the "Regional managed services" boxes mean

The two boxes labelled Regional managed services hold AWS services that:

- **are regional:** they live in ap-southeast-1 and belong to this legal entity's stack, unlike the global edge services;
- **are fully managed by AWS:** there are no servers, patches or capacity to manage, and AWS runs them across several AZs by default;
- **sit outside the VPC:** ECS tasks reach them over HTTPS through the NAT gateway, or through a VPC endpoint where one exists (the S3 and DynamoDB gateway endpoints). Access is controlled by IAM policies, not security groups.

The split into two boxes is only for layout: services used by the browser and the entry path are on the left, and services used by the back end are on the right.

| Left box | What it does in this design |
|---|---|
| **Cognito user pool** | Signs users in (OAuth 2.0 authorization-code flow; PKCE proposed), enforces MFA, stores password hashes, and puts the Admin/Agent group in the JWT. |
| **API Gateway HTTP API** | The one public API entry point. Validates the JWT and throttles traffic, then forwards through the VPC Link to the internal ALB. |
| **S3: frontend static files** | Private bucket holding the built frontend, served only through CloudFront (OAC). |
| **Cognito triggers (Lambda)** | Small functions Cognito runs after a login or a password-reset confirmation. They send those events straight to the SQS audit queue; if that fails, the login still succeeds and the event is logged to CloudWatch for replay ([Authentication events](../security/security-model.md#authentication-events)). |
| **S3: ID documents** | Private, KMS-encrypted bucket for client identity documents (synthetic only), uploaded with short-lived presigned URLs. The stored copy is the audit record of what the agent checked. |
| **Amazon SES** (deferred) | Would send the client a verification request e-mail. Deferred: agents verify NRIC documents manually for now, so nothing is sent. Added only if verification is automated later. |
| **EventBridge Scheduler** | Invokes the transaction-ingest Lambda about every 5 minutes, and starts chain-verify (daily), the alert worker (every 6 hours) and the health analysis task (daily, 08:00 SGT). |

| Right box | What it does in this design |
|---|---|
| **SQS audit queue** (standard, `audit-insert`) | One standard queue shared by every producer. Carries audit events to the auditor; order does not matter, and the auditor discards duplicates by `event_id`. |
| **SQS dead-letter queue** (`audit-insert-dlq`) | Keeps audit events that failed 5 times, for inspection and replay. |
| **S3 audit anchor (Object Lock)** | Stores the daily hash-chain head where it cannot be changed or deleted during the retention period. |
| **client-keys (DynamoDB)** | One item per client: the client UUID and that client's data key, stored only in its KMS-wrapped (encrypted) form. Used only by client-service. |
| **Amazon Bedrock (ap-southeast-1)** | Foundation models for the x-factors: alert summaries with private client context (x-factor 1), and plain-language wording of health-monitor findings (x-factor 3). |

## Components

Recorded in [ADR-0017](../adr/ADR-0017-microservices-per-business-area.md) (Proposed).

| Component | Responsibility | Runtime |
|---|---|---|
| **Frontend** (S3 + CloudFront) | Presentation layer only; no business logic. | Static files |
| **API Gateway HTTP API** | Single public API entry point; Cognito JWT validation; stage throttling. | Managed |
| **Internal ALB** | Load balancing across tasks in both AZs; path routing to services; HTTPS. | Managed |
| **user-service** | F1: create, update and disable users, password reset, logout; root admin protection; Cognito admin API calls; admin-wide activity view (all agents). | Java 25, ECS |
| **client-service** | F2: client profile CRUD, Appendix 2 validation, identity verification (manual: the agent checks the uploaded NRIC and sets the status), agent ownership rules; client activity view. Encrypts every client field except IDs and ownership with a per-client data key (envelope encryption, [Client data encryption (envelope encryption)](../security/security-model.md#client-data-encryption-envelope-encryption)). Currently implemented as `client-profile-management-service`, which also holds accounts until they are split out. | Go, ECS |
| **account-service** | F2: bank account create and delete. Checks that the client exists and belongs to the agent by calling client-service, instead of a database foreign key. | ECS (language to be decided) |
| **transaction-service** | F3: transaction query API. Must return a client's transactions only to the agent who owns that client; how that is enforced is an open decision ([open item 10](open-items.md#10-how-transaction-reads-enforce-agent-ownership)). | Java 25, ECS |
| **transaction-ingest** | F3: separate ingestion worker. Connects to the external SFTP server over SSH, reads ready files, validates and imports them, and moves each file to `processed/` or `rejected/`. | Java, AWS Lambda (VPC-attached), invoked by EventBridge Scheduler |
| **event-alert-service** (x-factor 1) | Market-news alerts. `api` mode serves `/api/alerts` to agents: list and view alerts, record a decision (`INFORM_CLIENT`, `MONITOR` or `DISMISS`) and give feedback; admins can read but not decide. The `worker` task, started every 6 hours, selects widely held instruments, calls the Tokyo web search with public terms, matches and scores, writes summaries with Bedrock in Singapore, and routes alerts to assigned agents. | Java 25, ECS service + scheduled ECS task |
| **dashboard-service** (x-factor 2) | Business dashboard for admins (bank-wide, per agent) and agents (own book). Stateless: fans out to aggregate endpoints on user-, client-, account- and transaction-service with the caller's JWT; 3-minute per-caller cache. | Java 25, ECS |
| **client-health-monitor-service** (x-factor 3) | Task API for relationship follow-up tasks. A daily scheduled analysis task (08:00 SGT) runs the deterministic rules engine (recurring-credit interruption, activity decline, sustained net outflow) and uses Bedrock only for wording, with a template fallback. | Java 25, ECS service + scheduled ECS task |
| **auditor** | Consumes audit events from SQS, appends them to the hash chain, and serves audit reads over internal gRPC. | Go, ECS |
| **chain-verify** | Daily hash-chain verification; writes the chain head to S3 Object Lock. Built from the auditor codebase and image. | Go, ECS scheduled task |
| **db-migrate** | Applies one service's versioned schema migrations to that service's database before the service deploys. One task definition per service. | ECS one-off task |
| **RDS for PostgreSQL** (users-db, clients-db, accounts-db, transactions-db, audit-db) | Relational ACID storage. One instance per service, each with a Multi-AZ standby. | Managed |
| **alerts-db, health-db** | The x-factor services' own RDS instances: holdings, instrument master, events, alerts and decisions (alerts-db); activity projection, signals, evidence, tasks and explanations (health-db). Each has its own outbox. | Managed |
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
| **AWS WAF** | Web application firewall on CloudFront, using AWS managed rule groups (common exploits, bad inputs, IP reputation). | NFR 8 names "firewalls" | Can be left out of staging (about USD 10/month); keep in prod |
| **ACM** | Free TLS certificates: one in us-east-1 for CloudFront (a CloudFront requirement) and one for the internal ALB. | NFR 8, Feature 2 (encryption in transit) | Only needed with a custom domain ([open item 2](open-items.md#2-domain-name)) |
| **Route 53** | DNS for the custom domain, pointing it at CloudFront. | Needed for ACM certificate validation | Yes, if no domain is bought; the default `*.cloudfront.net` address works |

### Identity and API entry (regional)

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **Cognito user pool** | Managed OAuth 2.0 / OIDC identity provider. Handles MFA, password hashing and storage, and Admin/Agent groups. user-service is already built on it. | Feature 1: OAuth 2.0, MFA, secure password storage | No |
| **Cognito triggers (Lambda)** | Login and user-initiated password-reset confirmation happen inside Cognito, not in our services. The `PostAuthentication` and `PostConfirmation_ConfirmForgotPassword` triggers are the only way to capture those events for the audit log. | Feature 1 audit: "login ... password changes" | No, unless login auditing is dropped |
| **API Gateway HTTP API** | Single public API entry point. Checks the Cognito JWT before any request reaches the VPC, and applies throttling. | NFR 8 (Zero Trust), NFR 5 (overload protection) | No |
| **API Gateway VPC Link ENIs** (both AZs) | Network interfaces that let API Gateway reach the internal ALB without exposing it to the internet. One per AZ, so the entry path survives the loss of an AZ. Its security group is the only one the ALB accepts traffic from. No extra charge. | NFR 2, NFR 8 | No (needed for a private ALB) |

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
| **ECS Fargate services** (user, client, account, transaction, event-alert, dashboard, health-monitor, auditor) | Runs the containerised services with no servers to manage. Target-tracking auto scaling keeps at least two tasks per service across two AZs. | NFR 1 (auto-scaling), NFR 2, NFR 11 (cloud native, containers) | No |
| **AWS Lambda: transaction-ingest** (VPC-attached) | The Feature 3 ingestion worker. Invoked on a schedule, it connects to the external SFTP server, imports ready files into transactions-db and audits each file. Costs almost nothing between runs ([Transaction ingestion (Feature 3)](data-flow.md#transaction-ingestion-feature-3)). | Feature 3 | No |
| **chain-verify** (scheduled ECS task) | Daily hash-chain verification and anchoring. Runs to completion and costs nothing between runs. | Spec: "Audit logs should be tamper-resistant" | No |
| **db-migrate** (one-off ECS task per service) | Applies each service's database migrations before its deployment, using a database user that is the only one allowed to change the schema. | NFR 3, NFR 10 (automated deployment) | Yes: migrations can run on service startup instead ([Databases](../database/schema.md#databases)) |
| **EventBridge Scheduler** | Invokes the transaction-ingest Lambda every few minutes and starts chain-verify, the alert worker and the health analysis task, with no always-on scheduler process. | Feature 3, audit integrity, Features 4–6 | No |
| **Alert worker, health analysis** (scheduled ECS tasks) | Batch work for x-factors 1 and 3. Runs once per schedule and costs nothing between runs; a timer inside the always-on services would run in every copy and restart with every deployment. | Features 4–6, NFR 1 | No |
| **ECR** | Private container image registry, with vulnerability scanning on push. | NFR 8 (security audits), NFR 10 (CI/CD) | No |

### Data and storage

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **RDS for PostgreSQL, one instance per service** (primary) | Relational ACID database. Each microservice owns its own instance, so no service can read another's data, and each can be scaled, maintained and fail independently. | Feature 2 ("relational database ... ACID"), NFR 3 and 11, NFR 4 | No |
| **RDS Multi-AZ standby** (one per database) | A synchronously replicated copy in the other AZ. RDS fails over to it automatically if the primary or its AZ fails. | NFR 2 (redundancy, failover, multiple AZs), NFR 9 | Run only in the high-availability demo week ([Environments and cost (target architecture)](../cost/budget.md)) |
| **S3: frontend static files** | Stores the frontend's built files, private behind CloudFront. | NFR 11 (separate presentation layer) | No |
| **S3: ID documents** | Private, KMS-encrypted storage for identity documents (synthetic NRIC only) uploaded during client verification. The agent checks each document manually; the stored copy is the audit evidence of what was checked. | Feature 2 "Verify Client Identity" | No |
| **RDS: alerts-db, health-db** | Own databases for the two x-factor services that store data, so neither reads another service's tables. Same instance size and Multi-AZ pattern as the other databases. | NFR 3 and 11 (service boundaries), Features 4–6 | Could share one instance with a database each, saving about USD 13–15/month, at the cost of isolation |
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
| **Amazon Bedrock** (ap-southeast-1) | Foundation models, billed per use: alert summaries with private client context (x-factor 1) and wording of verified health-monitor facts (x-factor 3). Kept in Singapore because client context must not leave the region. Model invocation has a kill switch and its own budget alert. | Features 4–6 (at least one must use AI) | No; x-factor 3 still works on templates if Bedrock is off |
| **Bedrock AgentCore web search** (ap-northeast-1, Tokyo) | Worldwide public web search for x-factor 1, restricted to approved domains, returning a public event report. In Tokyo only because the capability is not available in Singapore; it can move back when it is. | Feature 4 (market-news alerts) | Yes, but x-factor 1 then has no news source |

### Security, operations and governance

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **IAM** | Least-privilege role per ECS task and Lambda; an OIDC role so GitHub Actions can deploy without long-lived keys; only producer roles may send to the audit queue. | NFR 8 (Zero Trust), NFR 10 | No (free) |
| **KMS** | Encryption keys for RDS, S3, SQS, DynamoDB and backups. A symmetric customer-managed key wraps every per-client data key (envelope encryption): client-service calls `GenerateDataKey` for a new client and `Decrypt` to unwrap a stored key. | Feature 2 (encryption at rest), NFR 8 (GDPR) | No (about USD 1 per key per month) |
| **Secrets Manager** | Database credentials (RDS can manage and rotate each instance's master password here) and the ingestion worker's SSH private key. | NFR 8 (secure secret storage) | Could move to SSM SecureStrings to save about USD 0.40 per secret, losing managed rotation |
| **SSM Parameter Store** | Free storage for non-secret configuration and the SFTP server's public host key used for pinning. | NFR 3, NFR 8 | Yes, if everything moves to Secrets Manager |
| **GuardDuty** | Managed threat and intrusion detection, using CloudTrail, VPC flow and DNS logs. | NFR 8 names "intrusion detection" | Use the 30-day free trial, timed to cover the demo |
| **CloudTrail** | Records every AWS API call in the account: who changed which resource, and when. | NFR 8 (security audits), NFR 7 | No (one management-events trail is free) |
| **CloudWatch** | Logs, metrics, a dashboard and alarms for services, queues, databases and ingestion. | NFR 7 (monitoring, dashboards, alerting) | No |
| **SNS** | Sends CloudWatch alarms and budget alerts to the team by e-mail. | NFR 7 ("alerting mechanisms") | No (effectively free) |
| **AWS Backup** (daily plan) | One tag-based backup plan covering all seven RDS databases and the client-keys table, with a copy to a vault in ap-southeast-2 ([Parts of the architecture not shown on the diagram](#parts-of-the-architecture-not-shown-on-the-diagram) and [Why AWS Backup is included when RDS already has backups](../database/schema.md#why-aws-backup-is-included-when-rds-already-has-backups)). | NFR 2, NFR 5, NFR 9 | See [Why AWS Backup is included when RDS already has backups](../database/schema.md#why-aws-backup-is-included-when-rds-already-has-backups) |

### External and delivery

| Service | Why it is included | Requirement | Optional? |
|---|---|---|---|
| **Core banking SFTP server** (external, EC2) | Simulates the core banking system that sends the CRM its transactions. Drawn outside AWS because it is not part of the CRM. Runs as self-hosted OpenSSH on a `t4g.micro` EC2 instance in its own stack, not AWS Transfer Family, which costs about USD 216/month even when idle. | Feature 3: "Host an external server" | No |
| **Amazon SES** (deferred) | Would send the client an identity-verification request e-mail. Deferred: agents verify NRIC documents manually for now. SES is added only if verification is automated later, starting in sandbox mode (verified addresses only). | Feature 2: "System sends a verification request to the client" ([open item 19](open-items.md#19-client-verification-request-feature-2)) | Yes: not used until verification is automated |
| **GitHub Actions** (outside AWS) | Automated tests, image build and scan, Terraform plan/apply, and deployment. | NFR 10 ("demo a CI/CD"), NFR 13 | No |

## Parts of the architecture not shown on the diagram

These are left off the diagram to avoid overcrowding. They are still part of the target architecture and are built with it.

| Item | What it does | How it connects to the diagram |
|---|---|---|
| **ECS Service Connect and its Cloud Map namespace** | A private service registry plus an Envoy proxy sidecar in each task. Services call each other by name (for example `auditor:50051`); the proxy picks a healthy task in either AZ, balances each gRPC request, retries and ejects failing tasks, and reports metrics. No extra charge beyond the sidecar's CPU and memory. | Carries flow 8 (gRPC audit reads). Also carries service-to-service API checks that are not drawn: account-service checking a client ID with client-service, possibly the transaction ownership check ([open item 10](open-items.md#10-how-transaction-reads-enforce-agent-ownership)), and dashboard-service's fan-out (flow 12). Only ECS **services** can use it: the transaction-ingest Lambda makes no calls to other services, and the standalone health analysis task calls through the internal ALB instead. Supports NFR 1 and 2 on the audit read path; needed while gRPC reads are used. |
| **AWS Backup vault in ap-southeast-2 (DR region)** | Receives a cross-region copy of each daily database snapshot. Backup Vault Lock stops anyone deleting or shortening the retention of recovery points. | The diagram shows only the AWS Backup daily plan in the security band. The vault is used to rebuild the stack in ap-southeast-2 if Singapore is unavailable ([Why AWS Backup is included when RDS already has backups](../database/schema.md#why-aws-backup-is-included-when-rds-already-has-backups)). |
| **AWS Budgets** | Alerts at 50, 70, 85, 95 and 100% of the USD 300 limit, plus a separate alert for Bedrock spend. Required by the course budget rule (project_plan §23). | Sends alerts through SNS (shown in the security band). |
| **Security groups** | One per service, per database, for the ALB, the VPC Link and the ingest Lambda. They implement the allowed-traffic chain in [Identity and security](../security/security-model.md#identity-and-security). | Apply to every icon inside the VPC; not drawn as icons. |
| **Transactional outbox relay** | Not a separate component: a loop inside each service that reads unsent rows from the service's outbox table and sends them to SQS. The ingest Lambda has no long-running loop, so it relays its own `ingest_outbox` rows at the end of each run ([Transaction ingestion (Feature 3)](data-flow.md#transaction-ingestion-feature-3)). | Part of flow 6; the outbox table lives in each service's own database. |

## Network layout

Recorded in [ADR-0019](../adr/ADR-0019-edge-entry-chain.md) (Proposed).

- The VPC (`10.0.0.0/16`) has three subnet tiers in each AZ: public (`10.0.0.0/24`, `10.0.1.0/24`: NAT only), private app (`10.0.10.0/24`, `10.0.11.0/24`) and isolated DB (`10.0.20.0/24`, `10.0.21.0/24`). Nothing in the private or isolated tiers is reachable from the internet.
- Each service's RDS primary is in AZ-1a's isolated subnet and its Multi-AZ standby in AZ-1b's. After a failover the roles swap; the endpoint name stays the same.
- Each database instance has its own security group that allows port 5432 only from its owning service's security group.
- API Gateway enters the VPC through VPC Link network interfaces in both AZs' private app subnets; the ALB accepts traffic only from the VPC Link's security group.
- There is one NAT gateway per AZ, so outbound traffic survives the loss of an AZ.
- The external SFTP server is outside the CRM network. The ingestion Lambda reaches it outbound through the NAT gateway and internet gateway, and the server accepts connections only from the NAT gateway's Elastic IP.
- The free S3 and DynamoDB gateway endpoints keep traffic to those services off the NAT.

## Why ECS Fargate (and not EKS)

Recorded in [ADR-0018](../adr/ADR-0018-ecs-fargate-compute.md) (Proposed).

- **Cost:** an EKS cluster's control plane alone costs about USD 73/month before any worker nodes. ECS has no control-plane charge.
- **Fit:** the spec's "cloud native, containerisation, orchestration" principle is met by containers orchestrated by ECS. Kubernetes is given in the spec as an example, not a requirement.
- **Effort:** Fargate removes node management, so the team can explain and operate the whole platform within the course timeline.

## Relationship to existing documents

- [`nfr-notes.md`](nfr-notes.md) predates this target architecture. It keeps the same "one RDS instance per service" decision, but differs in several places. Once the ADRs listed in the [ADR index](../adr/README.md) are accepted, `nfr-notes.md` should be marked superseded where they differ:
  - **Edge:** `nfr-notes.md` describes an API Gateway private integration that resolves services through Cloud Map; this design uses API Gateway → VPC Link → internal ALB ([ADR-0019](../adr/ADR-0019-edge-entry-chain.md)).
  - **Resiliency:** `nfr-notes.md` says no retries or circuit breakers are applied; this design uses timeouts, retries with backoff and circuit breakers on outbound calls (gRPC audit reads, the SFTP connection).
  - **Region:** `nfr-notes.md` is single-region with no cross-region recovery; this design adds an AWS Backup cross-region copy to ap-southeast-2 ([ADR-0023](../adr/ADR-0023-rds-per-service-backup-no-cache.md)).
  - **Data segregation:** `nfr-notes.md` assumes a single legal entity; this design deploys one stack per legal entity ([ADR-0026](../adr/ADR-0026-one-stack-per-legal-entity.md)).
  - **WAF:** `nfr-notes.md` says the system has no WAF; this design puts AWS WAF on CloudFront.
  - **Message broker:** `nfr-notes.md` already records that the auditor reads from SQS ([ADR-0004](../adr/ADR-0004-auditor-kafka-to-sqs-migration.md)); this design extends that with a transactional outbox and one shared queue ([ADR-0020](../adr/ADR-0020-audit-write-path-outbox-shared-sqs.md)).
- Accepted ADRs it builds on: [ADR-0004](../adr/ADR-0004-auditor-kafka-to-sqs-migration.md) (auditor on SQS) and [ADR-0005](../adr/ADR-0005-cognito-security-across-features-1-and-2.md) (shared Cognito security). The new ADRs extend them; they do not supersede them.
- Feature branches with their own ADRs for the x-factors (`feat/event-driven-alerts` ADR-0004 to ADR-0016, `feat/business-dashboard` ADR-0001 to ADR-0004) differ in places; see [open item 4](open-items.md#4-reconcile-the-x-factor-branch-adrs-with-the-target-architecture). Those branches reuse ADR numbers already taken on `dev`, so their ADRs need renumbering when merged.
