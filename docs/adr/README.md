# Architecture Decision Record Index

Use this index to identify which ADRs are relevant before loading their full
text. A one-line summary is a routing aid, not a replacement for an applicable
ADR; humans and agents must read the complete record before making or reviewing
a decision it governs.

## Accepted decisions

- [ADR-0001](ADR-0001-user-directed-push-policy.md) — PRs are the default, while warned and explicitly authorized direct integration is allowed only for unprotected task and feature branches.
- [ADR-0002](ADR-0002-direct-dev-governance-updates.md) — Azeez authorizes narrowly scoped Git-workflow and agent-instruction updates directly to `dev`, subject to file allowlisting, warning, synchronization, and non-forced-push checks.
- [ADR-0003](ADR-0003-maintain-one-line-adr-index.md) — Every ADR must have a one-line entry in the ADR index, which agents scan for relevance before reading applicable decisions in full.
- [ADR-0004](ADR-0004-event-driven-alerts-mvp-charter.md) - Feature 4 is Event-Driven Client Alerts, owned by Azeez and Hayyun with approved MVP scope, data boundaries, guardrails, retention, and budget defaults.
- [ADR-0004](ADR-0004-auditor-kafka-to-sqs-migration.md) — Auditor service messaging migrates from Kafka to Amazon SQS (standard queue plus DLQ), superseding the "no message broker" note in `docs/architecture/nfr-notes.md`.
- [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) — Features 1 and 2 share Cognito identity, with service-side JWT validation and Agent-owned client/account authorization.
- [ADR-0006](ADR-0006-transaction-account-linkage-and-access.md) — Feature 3 links imports to CRM account UUIDs and serves authenticated transaction reads through User-service, with account ownership checked by client-profile (decisions 1–2 superseded by ADR-0037: transactions have their own database and are linked through account-service; decision 4 superseded by ADR-0035: ownership is now checked by account-service).
- [ADR-0017](ADR-0017-microservices-per-business-area.md) — One microservice and database per business area (accounts split from clients), with Go allowed for the auditor, client-service and account-service.
- [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md) — Extends ADR-0004: services with a database write each audit event to their own outbox in the business transaction (existing request-middleware producers may save it right after the request, because of the cost of changing them), and a relay sends it to the shared `audit-insert` queue; transaction ingest writes its event in the import's transaction; Cognito triggers fail open.
- [ADR-0035](ADR-0035-account-ownership-across-services.md) — account-service copies each account's owning agent from client-service at creation using the caller's token, checks ownership locally, and client-service refuses to delete a client with accounts; both checks fail closed; supersedes ADR-0006 decision 4 (decision 4 partly superseded by ADR-0036: only undeleted accounts block a client's deletion).
- [ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md) — Deleting a client or account sets `deleted_at` and keeps the row; deleted records are hidden from everyone and are not the same as `closed` accounts; uniqueness ignores deleted rows; deleting is not erasure; supersedes ADR-0035 decision 4 in part.
- [ADR-0037](ADR-0037-transactions-database-and-account-linking.md) — Transactions get their own database; the import links rows to accounts through account-service's `/internal/accounts/lookup`, authenticating with a Cognito client-credentials token, and fails closed; supersedes ADR-0006 decisions 1–2.
- [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md) — X-factor 2: stateless dashboard-service fanning out with the caller's JWT, a 3-minute per-caller cache, and best-effort audit events.
- [ADR-0038](ADR-0038-dashboard-metric-definitions.md) — X-factor 2: what each dashboard figure means; only figures the data supports exactly, no sums across currencies, deleted records never counted, no composite agent score.

## Superseded decisions

- [ADR-0004](ADR-0004-local-user-service-crud-profile.md) - Superseded by ADR-0005; records the historical local user-service profile that bypassed Cognito and authorization.

## Numbering note

The event-alert and base-feature branches independently allocated ADR-0004 and
ADR-0005 before integration. Links and full filenames are canonical until a
human-owned governance decision assigns replacement identifiers; no historical
record was renamed or deleted during the branch merge.

## Proposed decisions

Target-architecture ADRs, described in [`docs/architecture/system-overview.md`](../architecture/system-overview.md). Numbering starts at 0017 because feature branches already use 0001–0016.

- [ADR-0005](ADR-0005-alert-holdings-and-valuation-boundary.md) - Use service-owned synthetic holdings and reproducible, freshness-aware valuation snapshots for the alert MVP.
- [ADR-0006](ADR-0006-alert-processing-architecture.md) - Run bounded scheduled ECS batches with PostgreSQL idempotency and no new alert-specific broker.
- [ADR-0007](ADR-0007-alert-service-runtime-boundary.md) - Build one Java 25 Spring Boot module with separately enabled API and worker modes.
- [ADR-0008](ADR-0008-alert-database-topology-and-retention.md) - Use an isolated alert database on the approved shared lower-environment PostgreSQL cluster with Flyway and bounded retention.
- [ADR-0009](ADR-0009-agentcore-search-region-and-fallback.md) - Restrict public Web Search 1.2.0 to a governed Tokyo Gateway with quotas, kill switch, and no automatic fallback.
- [ADR-0010](ADR-0010-alert-ai-provider-and-safety.md) - Use Luna behind a public-data-only structured adapter that abstains on unsafe or invalid output.
- [ADR-0011](ADR-0011-alert-news-content-governance.md) - Allow only reviewed official sources, retain minimal cited metadata, and support correction and takedown.
- [ADR-0012](ADR-0012-alert-api-event-and-authorization-contracts.md) - Freeze versioned alert APIs/events with ownership-scoped agents and read-only admins.
- [ADR-0013](ADR-0013-alert-audit-delivery-and-failure-policy.md) - Use a transactional outbox and explicit fail-closed/queued-audit behavior for tamper-resistant audit delivery.
- [ADR-0014](ADR-0014-alert-observability-cost-and-kill-switches.md) - Add redacted metrics, hard quotas, budget alarms, and independent ingestion/model kill switches.
- [ADR-0015](ADR-0015-deterministic-alert-materiality-scoring.md) - Use versioned fixed-decimal exposure and materiality rules that fail closed on unusable valuations.
- [ADR-0016](ADR-0016-holdings-derived-search-universe.md) - Derive bounded public search terms from sufficiently common client holdings inside Singapore without exporting portfolio facts.
- [ADR-0018](ADR-0018-ecs-fargate-compute.md) — Run all services and scheduled tasks on ECS Fargate across two AZs with 2–6 tasks per service, rejecting EKS on cost and effort.
- [ADR-0019](ADR-0019-edge-entry-chain.md) — Enter through CloudFront with WAF, then API Gateway HTTP API (JWT check, throttling), a VPC Link and a private internal ALB.
- [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md) — The auditor is internal-only; owning services serve activity views and read audit data over gRPC through ECS Service Connect.
- [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) — Hash chain covering `parent_hash`, a daily chain-verify task anchoring the head in S3 Object Lock, a findings table, and crypto-shredding for erasure.
- [ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md) — One RDS PostgreSQL instance per service with a Multi-AZ standby, AWS Backup with a cross-region copy, and no cache tier.
- [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) — Import transactions from a self-hosted external SFTP server with a scheduled VPC-attached Lambda, a ready-file protocol and idempotent inserts.
- [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) — Extends ADR-0005: mandatory MFA, managed login with PKCE (unconfirmed), client-credentials service tokens, and Cognito triggers for login auditing.
- [ADR-0026](ADR-0026-one-stack-per-legal-entity.md) — Segregate data by deploying one full Terraform stack per legal entity; only Singapore is deployed.
- [ADR-0027](ADR-0027-observability-baseline.md) — Structured PII-masked logs, CloudWatch metrics and dashboard, alarms to SNS with named owners, GuardDuty and CloudTrail.
- [ADR-0028](ADR-0028-cicd-migrations-terraform.md) — GitHub Actions deploying through OIDC, one schema-migration task per service, and all infrastructure in Terraform.
- [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md) — Local dev, disposable staging, prod only for the final weeks, and Multi-AZ only in the demo week, to stay under USD 300.
- [ADR-0030](ADR-0030-client-data-envelope-encryption.md) — Encrypt client fields with per-client data keys kept in DynamoDB and wrapped by KMS, so deleting a key erases the client.
- [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md) — X-factor 1: a worker every 6 hours sends only public terms to AgentCore web search in Tokyo; summaries use Bedrock in Singapore; own alerts-db.
- [ADR-0032](ADR-0032-xf1-instrument-master-deterministic-matching.md) — X-factor 1: deterministic instrument master and matching with no vector similarity; search only instruments held by ≥ 2 clients and ≥ 5% of clients.
- [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md) — X-factor 3: deterministic rules in a daily 08:00 SGT task, with Bedrock in Singapore used only for wording and a template fallback.
- [ADR-0039](ADR-0039-dashboard-aggregate-endpoints.md) — X-factor 2: owning services serve dashboard figures on private `/aggregates` routes scoped by the caller's token and not audited there; agents' transaction totals go through user-service.

## Maintenance rule

Add or update exactly one concise entry whenever an ADR is added, superseded, or
has its status changed. Keep historical entries and identify the superseding ADR
rather than deleting an old decision from this index.
