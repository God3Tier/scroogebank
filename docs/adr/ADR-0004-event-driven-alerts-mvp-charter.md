# ADR-0004: Event-Driven Client Alerts MVP charter

- Status: Accepted
- Decision date/time: 2026-09-24 13:15:32 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- AI-assisted draft: Yes — Codex
- Related branch: `feat/event-driven-alerts`
- Related issue/PR: Not yet assigned
- Supersedes: None

## Context

The project requires three innovation features, with Feature 4 integrating AI.
The Event-Driven Client Alerts plan had a complete proposed workflow but could not
start because its feature slot, ownership, MVP boundaries, regional processing,
data source, access policy, volume, retention, and budget had not been approved.

The repository is hybrid rather than entirely Java: `user-service` is Java 25 and
Maven, while the auditor and client-profile services are Go 1.27.1. The current CI
workflow runs the Maven reactor and does not currently test those Go services.

## Decision

1. Dynamic Event-Driven Client Alerts is Feature 4 and fulfils the required AI integration.
2. Azeez is the feature owner and owns Search/AI/Platform. Hayyun owns CRM/Domain/Experience and leads final integration with Azeez collaborating.
3. The alert service will be a Java 25 Spring Boot Maven module, subject to a detailed service/runtime ADR and inclusion in the existing Maven reactor.
4. MVP holdings and valuations use a small synthetic, feature-owned dataset with stable instrument identifiers, currencies, timestamps, and explicit stale/missing states. No live market feed is used.
5. Public discovery uses AgentCore Web Search in Tokyo with approved domain/date filters, a 1,000-query monthly quota, and a kill switch.
6. GPT-5.6 Luna may use global cross-region inference for public news only. Client identities, accounts, holdings, exposure, internal notes, agent identities, and audit data must not enter search or model requests.
7. Initial sources are regulators, exchanges, and official issuer investor-relations sites. A commercial publisher requires a documented terms/licensing review before allowlisting.
8. EventBridge Scheduler invokes a bounded ECS batch task. The MVP adds no alert-specific broker and reuses the approved auditor integration for audit delivery.
9. Admins have read-only oversight across alerts. Only the assigned agent may record `INFORM_CLIENT`, `MONITOR`, or `DISMISS` decisions.
10. Jev, embeddings, and semantic retrieval are outside the MVP.
11. The MVP creates at most ten new alerts per agent per day.
12. News metadata, prompts, and briefs are retained for 90 days; audit events are retained for one year. Only synthetic project data is used.
13. The feature has a USD 25 monthly operating alert and a USD 50 development hard cap, both subordinate to the USD 300 project-wide hard limit. The protected USD 100 contingency is not authorized.

## Consequences

### Positive

- Feature ownership, scope, privacy boundary, and cost envelope are explicit.
- Azeez can begin the isolated AgentCore spike while Hayyun freezes contracts.
- Java alert code can join the Maven reactor already exercised by CI.
- The synthetic holdings boundary avoids dependence on an unavailable live market-data system.

### Trade-offs and risks

- Tokyo search and global model inference introduce cross-region availability, latency, and public-data governance concerns.
- Synthetic holdings demonstrate the workflow but do not prove production market-data integration.
- The repository's Go services remain outside the current Maven-only CI job; this ADR does not accept that gap as adequate Go coverage.
- This charter does not replace the detailed architecture, data, AI, source, API, audit, or operations ADRs required by the feature plan.

## Validation

- The AgentCore spike must measure Singapore-to-Tokyo latency, reliability, and actual cost using public synthetic queries.
- Contract fixtures must prove that private CRM fields cannot enter search or model payloads.
- CI must build and test the new Java module through the root Maven reactor.
- Cost counters and controls must enforce the query, monthly-alert, and development-cap thresholds.
- Authorization tests must prove own-client agent access and read-only admin behavior.
