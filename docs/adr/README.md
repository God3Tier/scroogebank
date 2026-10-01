# Architecture Decision Record Index

Use this index to identify which ADRs are relevant before loading their full
text. A one-line summary is a routing aid, not a replacement for an applicable
ADR; humans and agents must read the complete record before making or reviewing
a decision it governs.

## Accepted decisions

- [ADR-0001](ADR-0001-user-directed-push-policy.md) - PRs are the default, while warned and explicitly authorized direct integration is allowed only for unprotected task and feature branches.
- [ADR-0002](ADR-0002-direct-dev-governance-updates.md) - Azeez authorizes narrowly scoped Git-workflow and agent-instruction updates directly to `dev`, subject to file allowlisting, warning, synchronization, and non-forced-push checks.
- [ADR-0003](ADR-0003-maintain-one-line-adr-index.md) - Every ADR must have a one-line entry in the ADR index, which agents scan for relevance before reading applicable decisions in full.
- [ADR-0004](ADR-0004-event-driven-alerts-mvp-charter.md) - Feature 4 is Event-Driven Client Alerts, owned by Azeez and Hayyun with approved MVP scope, data boundaries, guardrails, retention, and budget defaults.
- [ADR-0004](ADR-0004-auditor-kafka-to-sqs-migration.md) - Auditor service messaging migrates from Kafka to Amazon SQS (standard queue plus DLQ), superseding the "no message broker" note in `docs/architecture/nfr-notes.md`.
- [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) - Features 1 and 2 share Cognito identity, with service-side JWT validation and Agent-owned client/account authorization.

## Proposed decisions

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

## Superseded decisions

- [ADR-0004](ADR-0004-local-user-service-crud-profile.md) - Superseded by ADR-0005; records the historical local user-service profile that bypassed Cognito and authorization.

## Numbering note

The event-alert and base-feature branches independently allocated ADR-0004 and
ADR-0005 before integration. Links and full filenames are canonical until a
human-owned governance decision assigns replacement identifiers; no historical
record was renamed or deleted during the branch merge.

## Maintenance rule

Add or update exactly one concise entry whenever an ADR is added, superseded, or
has its status changed. Keep historical entries and identify the superseding ADR
rather than deleting an old decision from this index.
