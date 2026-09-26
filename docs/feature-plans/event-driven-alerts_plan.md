# Dynamic Event-Driven Client Alerts — Feature Plan

## 1. Plan Metadata

| Field | Value |
|---|---|
| Status | **APPROVED FOR MILESTONE 0; IMPLEMENTATION REQUIRES PREREQUISITE ADRS/CONTRACTS** |
| Innovation slot | Feature 4 (required AI integration) — confirmed 2026-09-24 |
| Human feature owner | Azeez — confirmed 2026-09-24 |
| Delivery team | Azeez — Search/AI/Platform; Hayyun — CRM/Domain/Experience |
| Proposed feature branch | `feat/event-driven-alerts` |
| Canonical plan location | `docs/feature-plans/event-driven-alerts_plan.md` |
| Current draft location | `docs/feature-plans/event-driven-alerts_plan.md` |
| Last reviewed against repository | 2026-09-24 (Asia/Singapore) |
| Repository baseline | `origin/dev` at `544ff66` |

The feature charter and recommended MVP defaults were approved by Azeez on
2026-09-24 and are recorded in ADR-0004. Substantial implementation must not start
until the detailed prerequisite ADRs and API/data contracts named in this plan are
accepted.

Plan work is performed through `task/event-driven-alerts/plan` into
`feat/event-driven-alerts`.

## 2. Repository Alignment and Known Gaps

This plan follows `project_plan.md`, `AGENTS.md`, `CLAUDE.md`, and the currently
available service documentation. The following facts constrain the design:

- Feature 4 is confirmed as Dynamic Event-Driven Client Alerts. Features 5 and 6
  remain unconfirmed.
- The repository is hybrid: `user-service` uses Java 25/Spring Boot/Maven, while
  the auditor and client-profile services use Go 1.27.1. The current CI workflow
  runs `mvn -B verify` and does not test the Go services. This feature will use a
  Java 25 Maven module in the existing reactor; CI coverage for the Go services is
  a separate repository gap and must not be described as Java coverage.
- The current client/account schema has clients and bank accounts, but no
  securities, holdings, positions, prices, or portfolio valuations. Portfolio
  exposure cannot be calculated until a source and ownership model for those data
  are approved.
- Agents must only access their own clients. Admin visibility must follow an
  agreed role policy.
- The current NFR notes describe synchronous REST and “no message broker,” but the
  auditor service and local Compose configuration already use Kafka. This
  documentation/architecture inconsistency must be resolved before this feature
  chooses Kafka, EventBridge, SQS, or a synchronous job flow.
- The expected ADR, OpenAPI, schema, testing, runbook, cost, and AI-usage documents
  are not all present on the current `dev` baseline.
- `docs/architecture/nfr-notes.md` describes an AWS-only deployment using Cognito,
  API Gateway, ECS, Cloud Map, and one Multi-AZ RDS instance per service, but the
  referenced infrastructure is not present in the current `dev` working tree.
  Those claims must be reconciled with the actual infrastructure branch before
  this feature depends on them.
- Amazon Bedrock AgentCore Web Search is currently available in Asia Pacific
  (Tokyo), not Asia Pacific (Singapore). A Singapore-hosted CRM can call the Tokyo
  endpoint, but that is a cross-region dependency and requires an ADR, explicit
  IAM/network configuration, failure handling, latency measurement, and cost
  monitoring.
- GPT-5.6 Luna can be invoked from Singapore using global cross-region inference,
  but processing is not guaranteed to remain in Singapore or Tokyo. Only public
  news content may be sent to search or Luna; private CRM processing remains in
  the Singapore application boundary.

These are delivery blockers, not details to silently fill in during coding.

## 3. Problem Statement and Business Value

Relationship managers cannot continuously review every financial-news item that
may affect their clients. The proposed feature identifies potentially material
public events, relates them to supported instruments, calculates affected-client
exposure with deterministic logic, and presents an evidence-backed briefing to the
responsible agent.

The value is faster, more consistent awareness while preserving human judgment.
The feature is decision support only: it does not provide autonomous financial
advice, contact clients, execute trades, or change portfolios.

## 4. Proposed User Outcome

An authenticated agent can see a prioritized alert for a client they own, inspect
the source and scoring factors, and choose one of:

- `INFORM_CLIENT`
- `MONITOR`
- `DISMISS`

An authorized admin may view all alerts for oversight but cannot record an agent's
`INFORM_CLIENT`, `MONITOR`, or `DISMISS` decision. Every decision and every
AI-generated recommendation is auditable.

## 5. Scope

### 5.1 MVP Scope

- Discover a small, approved set of public financial-news results on a schedule
  through the Amazon Bedrock AgentCore Web Search connector in Tokyo, subject to
  ADR approval and a retrieval benchmark.
- Use connector version `1.2.0` or later with administrator and request-level
  domain filters, inclusive publication-date filters, and bounded result counts.
- Deduplicate articles and retain source provenance.
- Match explicit company, ticker, sector, or approved keyword references against
  a supported instrument catalogue.
- Classify candidate events through a provider-independent interface.
- Use deterministic application logic to map affected instruments to holdings,
  calculate exposure, and score alert materiality.
- Generate a structured agent briefing only after deterministic filters pass.
- Display alerts in the existing CRM rather than creating a separate product.
- Enforce agent/client ownership on every alert and feedback operation.
- Record agent feedback and required audit events.
- Demonstrate graceful fallback when an AI provider or news source is unavailable.
- Measure quality, latency, failure rate, and cost using synthetic project data.

### 5.2 Explicit Non-Scope for MVP

- Automatic client contact or client-facing generated messages.
- Autonomous investment advice, trading, rebalancing, or risk-policy decisions.
- Training a model from synthetic agent decisions.
- Broad web crawling or unlicensed storage of third-party article content.
- Real client, portfolio, or confidential bank data.
- Real-time market-data trading infrastructure.
- Vector search, external classifiers such as Jev, or a learned relevance model
  unless an ADR and benchmark justify each addition after the MVP.
- Native Bedrock `bedrock-mantle` Web Search, because it is not currently
  available in Tokyo or Singapore and would introduce a second search path.
- A paid financial-news API unless AgentCore retrieval fails the agreed coverage,
  relevance, reliability, or cost thresholds.
- Cross-region disaster recovery or multi-legal-entity deployment beyond the
  repository's documented single-entity assumption.

## 6. High-Level MVP Flow

```text
EventBridge schedule in Singapore
          |
          v
AgentCore Web Search 1.2.0 in Tokyo
  - approved domains only
  - since-last-run date window
  - public ticker/event terms only
          |
          v
Minimal result storage and URL deduplication
          |
          v
Deterministic entity/keyword pre-filter
          |
          v
Provider-independent classification using Luna candidate
  - public search result context only
  - global cross-region inference
          |
          v
Instrument match
          |
          v
Deterministic holding/exposure calculation
          |
          v
Configurable alert threshold
          |
          +---- below threshold ----> retain metrics; no briefing
          |
          v
Structured AI briefing with source provenance
          |
          v
Authorized CRM agent
          |
          +---- Inform / Monitor / Dismiss
          |
          v
Feedback and tamper-resistant audit trail
```

AI is limited to event interpretation and clear briefing generation. Identity,
authorization, ownership, portfolio matching, exposure, scoring thresholds, and
workflow state transitions remain deterministic.

### 6.1 Proposed Regional and Data Boundary

```text
PUBLIC DATA PLANE

Singapore scheduler/service
        |
        | public ticker, issuer, sector, event terms, date range
        v
AgentCore Web Search — Tokyo (`ap-northeast-1`)
        |
        | title, URL, snippet, publication date
        v
Luna classifier — global cross-region inference
        |
        | structured public event only
========|====================================================
        v
PRIVATE CRM PLANE — Singapore

Instrument normalization -> holdings -> exposure -> alert -> agent
```

Search and classification requests must never contain client IDs, names, account
numbers, holdings, exposure, internal notes, agent identities, or audit data. The
Singapore application stores only permitted result metadata, required citations,
deduplication identifiers, and derived structured events; it must not bulk-copy or
reproduce search-result content.

### 6.2 Can the Entire Feature Run in Singapore?

Not with the proposed managed search capability at the time of this review.
AgentCore Gateway, Runtime, Identity, Observability, and other general AgentCore
capabilities are available in Singapore, but the specific managed **Web Search
Tool** connector is not. Its supported regions are currently N. Virginia, Ireland,
and Tokyo.

The proposed compromise is:

- keep the CRM, alert service, scheduler, database, authentication, authorization,
  portfolio matching, feedback, audit processing, and monitoring in Singapore;
- place only the AgentCore Gateway Web Search connector in Tokyo;
- send only public issuer/ticker/event terms to Tokyo;
- return public titles, URLs, snippets, and publication dates to Singapore; and
- perform every client-specific operation in Singapore after public-event
  classification.

If the team requires strict Singapore-only processing, it must reject AgentCore
Web Search for now and evaluate a Singapore-hosted custom provider adapter (for
example, an approved financial-news API called by the Singapore service). That
alternative still requires a data-egress and licensing review because the external
provider may process requests outside Singapore.

### 6.3 AWS Services Used by the Proposed MVP

The entries below are proposed implementation choices, not accepted decisions.
The prerequisite ADRs must confirm them before infrastructure is created.

| AWS capability | Region | Purpose | MVP configuration / constraint |
|---|---|---|---|
| Amazon Cognito | Singapore | Existing user authentication and Admin/Agent roles | Reuse the existing user pool; no alert-specific identity store |
| Amazon API Gateway HTTP API | Singapore | Existing public API entry point | Add `/api/alerts` routes only after the OpenAPI and authorization contract is approved |
| Amazon ECS on Fargate | Singapore | Run the Java 25 alert API and scheduled worker | Prefer one image with API and worker entry points; autoscaling and task sizing require measurement |
| Amazon ECR | Singapore | Store the alert-service container image | Lifecycle policy removes unneeded images |
| Amazon EventBridge Scheduler | Singapore | Start bounded discovery runs | Proposed cadence: every four hours; invoke an ECS task with retry limits |
| Amazon Bedrock AgentCore Gateway + Web Search Tool `1.2.0` | Tokyo | Search current public web sources | Four grouped searches/run, maximum ten results/search, enforced domain allowlist and date window |
| Amazon Bedrock GPT-5.6 Luna through `bedrock-runtime` | Invoked from Singapore using Global CRIS | Classify public events and generate constrained briefs | Provider adapter, structured validation, public content only, explicit token/output caps |
| Amazon RDS for PostgreSQL | Singapore | Persist search metadata, structured events, alerts, decisions, and processing watermarks | Whether to provision a service-owned instance or use another approved topology is an ADR and cost gate |
| AWS Secrets Manager | Singapore | Store configuration secrets if an approved fallback provider needs credentials | AgentCore uses IAM; never place secrets in source or task definitions |
| AWS IAM | Singapore and Tokyo | Least-privilege service/task/Gateway permissions | Separate execution roles; scope AgentCore invocation to the approved Gateway/tool ARN |
| AWS KMS-backed encryption | Singapore and Tokyo where supported | Encrypt RDS, secrets, logs, and service data | Reuse project key strategy; no feature-specific key without justification |
| Amazon CloudWatch | Singapore and Tokyo | Logs, metrics, dashboards, alarms | Track schedule health, search count/cost, cross-region latency, model tokens, failures, and backlog |
| AWS CloudTrail | Singapore and Tokyo | Control-plane and supported AgentCore invocation evidence | Enable only required data events and apply bounded retention to control cost |
| AWS Budgets and Cost Anomaly Detection | Billing scope | Protect the USD 300 whole-project limit | Feature tags/metrics, search quota alarms, and model-spend alerts |

The MVP does **not** require AgentCore Runtime/Harness, Bedrock Knowledge Bases,
OpenSearch/vector storage, SageMaker, Step Functions, SQS, SNS, MSK, or ElastiCache.
Adding any of them requires a demonstrated need, cost estimate, and ADR. The
existing auditor integration remains an application dependency rather than a new
AWS service choice in this plan.

## 7. Functional Requirements

### FR-1 — News Ingestion

- Invoke AgentCore Web Search in Tokyo from a bounded Singapore scheduler. The
  initial operating target is four grouped searches every four hours, not a query
  per client or per instrument.
- Pin the connector to version `1.2.0` or later.
- Search only team-approved domains and public instrument/event terms. Configure a
  gateway-level domain allowlist that callers cannot relax.
- Pass a request-level publication window from the last successful watermark to
  the current run. Apply a small overlap and deduplicate results to avoid missing
  boundary events.
- Request no more than ten results per grouped search for the MVP.
- Store source name, canonical URL, publication time, retrieval time, title,
  permitted content or excerpt, and a deduplication hash.
- Respect source terms, licensing, retention requirements, and rate limits.
- Preserve and display source URLs/citations wherever search-derived content is
  shown to an agent.
- Do not bulk extract, store, or reproduce AgentCore search content or use it to
  construct a competing search index.
- Make ingestion idempotent.
- Record processing status and retry count without exposing secrets.

### FR-2 — Candidate Retrieval

- Maintain a supported instrument catalogue with ticker, issuer, aliases, sector,
  industry, and approved keywords.
- Direct matches produce candidate instrument links with an explanation.
- The MVP must work without embeddings. Semantic retrieval is a later option and
  needs benchmark evidence, cost estimates, and an ADR.

### FR-3 — Event Classification

The application must depend on a provider-neutral contract such as:

```java
public interface EventClassifier {
    EventClassification classify(NewsArticle article);
}
```

The returned structure must be schema-validated and include event type, severity,
materiality, sentiment when applicable, confidence, affected-instrument candidates
with evidence, provider/model version, classification time, and fallback status.

The initial event taxonomy is proposed as:

```text
EARNINGS
MERGER_ACQUISITION
REGULATORY
LEGAL
MANAGEMENT
PRODUCT
MACROECONOMIC
GEOPOLITICAL
CREDIT
OTHER
```

The taxonomy and confidence semantics must be approved in an API/data-contract ADR
before they become persisted contracts.

### FR-4 — Portfolio Matching

- Query a team-approved holdings source; the model must never search arbitrary CRM
  records.
- Match only normalized instrument identifiers.
- Return only clients the requesting agent is authorized to access.
- Treat missing or stale price/valuation data as an explicit state, not zero.

**Approved MVP boundary:** no holdings model or valuation source exists in the
current repository, so the alert service will own a small synthetic holdings and
valuation dataset with stable instrument IDs, currencies, valuation timestamps,
and explicit stale/missing states. It is demonstration data, not a live market
feed. The holdings/data ADR must freeze the schema, freshness rule, and later
replacement boundary before implementation.

### FR-5 — Exposure and Alert Scoring

Exposure must be calculated deterministically:

```text
affected holding market value / total portfolio market value
```

The formula is valid only when valuation timestamps and currencies are compatible.
Currency conversion, stale valuations, short positions, and missing totals must be
defined before implementation.

The MVP score may use configurable weights for event severity, exposure,
retrieval relevance, and classifier confidence. Weights and thresholds are
prototype settings, not UBS policy, and must be versioned so an alert can be
reproduced.

### FR-6 — Agent Briefing

- Generate a briefing only for alerts that pass the deterministic threshold.
- Return a schema-validated headline, concise summary, client-impact explanation,
  reason for alert, uncertainty statement, source references, and suggested review
  actions.
- Label content as AI-generated decision support and do not overstate certainty.
- Do not include direct client identifiers, contact details, account numbers,
  internal notes, or raw holdings in an external-model prompt.
- Prefer pseudonymous references and the minimum derived exposure metrics needed.
- Reject or quarantine malformed or unsafe model output.

### FR-7 — Dashboard and Feedback

- Show unread/active alerts in the existing agent dashboard.
- Explain why each alert appeared, including source, event severity, exposure,
  retrieval basis, confidence, score version, and generation status.
- Permit only valid transitions to `INFORM_CLIENT`, `MONITOR`, or `DISMISS`.
- Record the actor, time, prior state, new state, and optional reason.
- Do not send a client communication as a side effect of a state transition.

## 8. Proposed API, Event, and Job Contracts

Exact contracts require OpenAPI/event-schema review. Candidate REST endpoints are:

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/alerts` | Agent: own clients; Admin: read-only oversight across clients | List authorized alerts |
| `GET` | `/api/alerts/{alertId}` | Same ownership rule | Get evidence and briefing |
| `POST` | `/api/alerts/{alertId}/decisions` | Assigned agent only | Record Inform/Monitor/Dismiss |
| `POST` | `/internal/alerts/reprocess/{articleId}` | Admin/service role only | Controlled retry; optional |
| `GET` | `/internal/alerts/health` | Platform policy | Health/readiness |

Scheduled jobs are proposed for ingestion, classification retries, and retention.
If asynchronous events are approved, every event must have a version, event ID,
correlation ID, occurred-at timestamp, producer, schema, and idempotency behavior.
Candidate names are `NewsArticleIngested`, `EventClassified`,
`ClientAlertCreated`, `ClientAlertGenerationFailed`, and `AlertDecisionRecorded`.

EventBridge Scheduler invoking a bounded ECS batch task is the approved MVP trigger;
no additional broker is introduced for alert processing. The existing auditor
integration remains the audit path. Exact retry, idempotency, and audit-delivery
contracts remain ADR-gated. AgentCore Web Search is the approved discovery
provider candidate; native Bedrock Mantle Search is not part of the MVP.

## 9. Proposed Data Model

The owning service and database topology require an ADR. Candidate entities are:

| Entity | Minimum fields |
|---|---|
| `news_article` | ID, source, canonical URL, permitted content/excerpt, content hash, published/retrieved timestamps, processing status |
| `instrument` | Stable ID, ticker, issuer, aliases, sector, industry, active flag |
| `article_instrument_match` | Article ID, instrument ID, match method, score, evidence |
| `classified_event` | Event ID, article ID, taxonomy values, confidence, provider/model/version, prompt/schema version, timestamps |
| `holding` or read contract | Client ID, instrument ID, quantity/value, currency, valuation timestamp, provenance |
| `client_alert` | Alert ID, event ID, client ID, agent ID, exposure, score, score version, priority, status, timestamps |
| `agent_brief` | Alert ID, structured result, provider/model/version, generation status, source references |
| `alert_feedback` | ID, alert ID, actor ID, decision, reason, timestamp |

Required constraints include unique article hashes, unique event/client alert pairs,
state checks, indexes for agent/status/time queries, and retention markers. Cross-
service database reads are not allowed unless an accepted ADR changes the current
service-ownership model.

Every migration must be versioned, tested, backward-compatible where practical,
deployed first to a lower environment, and include rollback or forward-fix notes.

## 10. Authorization and Privacy

- Cognito/OIDC authentication and service-level token validation remain the
  baseline described by the current user-service documentation.
- An agent may list, view, or decide only alerts for clients assigned to that
  agent. Enforce object-level authorization server-side on every request.
- Admin access to alerts and briefs is **TBD** and needs a human policy decision.
- Internal ingestion/retry endpoints require a service role or explicit admin
  permission; they are not ordinary agent endpoints.
- Separate public news/instrument metadata from private CRM records.
- The Tokyo search request and global Luna request receive only public search
  terms/results. They receive no client identity or raw CRM/portfolio data.
- Client/holding retrieval, exposure calculation, alert storage, authorization,
  feedback, and audit processing remain in the Singapore deployment.
- The cross-region request path, encryption, IAM, CloudTrail coverage, data
  transfer, provider retention, and acceptable-use conditions require explicit
  ADR review.
- Stored prompts/responses, logs, traces, and metrics must exclude secrets and
  unnecessary PII.
- Encrypt data in transit and at rest, use least-privilege IAM, and keep secrets
  outside the repository.
- Use only synthetic data for development, evaluation, and demonstration.
- Retention/deletion behavior must cover articles, briefs, feedback, and links to
  deleted clients.

## 11. Audit Logging

The existing auditor capability should be reused only after its production
contract, transport, authorization, and availability are reconciled with the
architecture documentation. At minimum, record:

- ingestion success/failure and article provenance;
- classification and briefing provider/model/schema versions;
- alert creation, suppression, retry, failure, and score version;
- every agent decision with before/after state;
- administrative reprocessing or threshold/configuration changes; and
- authorization failures for protected alert resources, without logging PII.

Audit entries must contain actor/service, action, time, affected entity, outcome,
and correlation ID. They must be access-controlled, tamper-resistant, queryable,
and free of unnecessary article content and client data.

## 12. Failure and Degraded Behavior

| Failure | Required behavior |
|---|---|
| News source unavailable | Bounded retry with backoff; preserve last-success metric; alert operators after threshold |
| Tokyo AgentCore endpoint unavailable from Singapore | Skip the discovery run, retain the watermark, alert operators, and retry within a bounded schedule; do not generate fabricated events |
| Duplicate article | Idempotently reuse or ignore the existing record |
| Classifier unavailable or timeout | Use an approved rule fallback or mark pending; never fabricate a result |
| Invalid model output | Reject, record safe diagnostics, and retry only within a bounded policy |
| Brief generator unavailable | Preserve the structured alert and show generation pending/failed |
| Holdings/valuation missing or stale | Do not compute misleading exposure; mark not evaluable |
| Semantic retrieval unavailable | Continue with direct entity/keyword matching if healthy |
| Audit write unavailable | Follow the audit ADR; sensitive state changes must not silently lose auditability |
| Partial job failure | Resume idempotently from persisted status and avoid duplicate alerts |

Dead-letter handling, retry counts, timeouts, circuit breaking, ordering, and
backpressure must be decided with the chosen job/event architecture.

## 13. Observability and Operations

Use correlation IDs from ingestion through feedback. Define dashboards and alarms
for search queries by schedule/group, results returned, query quota remaining,
Tokyo request latency/failures, results deduplicated/rejected/pending, end-to-end
and per-stage latency, Luna calls/tokens/failures/cost, generation failures and
retry/dead-letter depth, stale valuations, job freshness, and useful-alert,
dismissal, and monitor rates on synthetic/evaluation data.

Logs must be structured and redact sensitive payload fields. Runbooks must cover
provider outage, stuck jobs, replay, bad model output, threshold rollback, and
cost-spike response.

## 14. Quality and Evaluation

Create a versioned, manually reviewed dataset of licensed or synthetic public-news
examples. Start small enough for reliable labelling; expand only when label quality
can be maintained. The earlier unvalidated 200–500 target is not a delivery
requirement.

Measure retrieval precision/recall and Recall@K; event/materiality precision,
recall, and F1; confidence error/calibration; end-to-end and model latency;
provider/fallback failures; cost per article and alert; and useful-alert/dismissal
rates during controlled evaluation.

All approaches must use the same held-out examples. Provider selection requires
documented thresholds and human review of harmful false positives and negatives.

## 15. Test Plan

| Layer | Required coverage |
|---|---|
| Unit | Deduplication, taxonomy validation, matching, exposure math, currency/staleness rules, scoring, state transitions, redaction |
| Contract | REST/OpenAPI schemas, event versions, structured model output, holdings read contract, audit contract |
| Authorization | Own-client access, other-agent denial, admin policy, service endpoints, invalid/expired tokens |
| Integration | Migrations, provider adapters, audit delivery, job retry/idempotency |
| Failure path | Timeouts, malformed output, duplicate delivery, stale data, partial jobs, provider/audit outage |
| E2E | One synthetic article becomes one explainable alert for the correct agent; feedback is stored and audited |
| Security | Prompt injection, output sanitization, PII/log leakage, SSRF-safe URL handling, secret scanning |
| Performance | Ingestion burst, alert-list latency, backlog recovery, concurrency without duplicate alerts |
| Cost | Bounded model calls after pre-filtering; measured cost against allocation |
| Recovery | Backup/restore and replay without duplicate notifications |

CI should run validation, static checks, unit tests, builds, security/dependency
scans, contract/integration tests where feasible, and infrastructure validation.

## 16. Cost Constraints

This feature has a USD 25 monthly operating alert and a USD 50 development hard
cap. Both remain subordinate to the USD 300 whole-project hard limit and do not
grant access to the protected USD 100 contingency. Record expected idle,
evaluation, load-test, and demo costs in
`docs/cost/budget.md` and `docs/cost/resource-register.md`.

The complete project must remain within the USD 300 hard operating limit. The
separate USD 100 contingency needs explicit team approval and a human-owned
decision record. Estimates must include news licensing, compute, database/storage
and backups, any event infrastructure, vector storage, model usage, logging,
metrics, alarms, data transfer, retention, and testing.

AgentCore Web Search is currently priced at USD 7 per 1,000 queries. The proposed
MVP schedule is four grouped searches every four hours:

```text
4 search groups × 6 runs/day × 30 days = 720 queries/month
720 / 1,000 × USD 7 = USD 5.04/month
```

Set an initial application quota of 1,000 queries/month (USD 7 search charge), a
budget alert below that quota, and a kill switch. Gateway calls, Tokyo/Singapore
data transfer, Luna tokens, EventBridge, compute, storage, logging, alarms, and
taxes remain additional costs and must be measured. A query-per-client or
query-per-ticker design is prohibited unless a later cost ADR explicitly approves
it.

Controls must cap articles per run, model calls per article, output length, retry
count, concurrency, and retention. A kill switch must disable ingestion and model
invocation without disabling access to existing alerts.

## 17. Required ADRs Before Implementation

Each ADR needs a human owner and must cover security, privacy, cost, operations,
validation, and alternatives.

1. **Feature approval and boundaries — accepted in ADR-0004** — Feature 4,
   ownership, MVP guardrails, retention, and budget defaults are confirmed.
2. **Holdings and valuation source/ownership** — define data, freshness, currency,
   service boundary, and synthetic demo source.
3. **Processing architecture** — scheduled jobs versus EventBridge, SQS/SNS,
   Kafka, or another mechanism; reconcile the “no broker” note and auditor code.
4. **Service/runtime boundary** — extend an existing service or add a Java 25
   service; any non-Java deployed runtime needs explicit justification.
5. **Database topology and schemas** — ownership, RDS impact, retention, backup,
   migrations, and cross-service access.
6. **Search, regional processing, and fallback** — approve AgentCore Web Search
   `1.2.0` in Tokyo from the Singapore deployment; record domain/date filtering,
   IAM, network path, latency, query quota, acceptable-use/storage limits, citation
   requirements, outage behavior, and financial-API fallback criteria.
7. **AI provider/model and fallback** — evaluate Luna as the classifier/briefing
   model; explicitly accept or reject global cross-region processing and document
   data boundaries, thresholds, explainability, safety, latency, and cost.
8. **News source/content governance** — approve the domain allowlist, content
   licensing, minimal storage, retention, provenance, and takedown behavior.
9. **API/event contracts and authorization** — routes, events, versioning, agent
   ownership, and admin visibility.
10. **Audit integration** — transport, fail-open/fail-closed behavior, retention,
   access, and tamper resistance.
11. **Observability and cost controls** — metrics, alarms, the 1,000-query initial
    quota, model-token caps, and kill switch.

Semantic retrieval and Jev each require a later ADR only if benchmark evidence
shows material value beyond the MVP.

## 18. Implementation Milestones and Gates

### Milestone 0 — Make the Feature Ready

- Confirm Azeez as the human feature owner and the two-person delivery assignment:
  Azeez for Search/AI/Platform and Hayyun for CRM/Domain/Experience.
- Confirm the innovation slot and MVP boundary.
- Move this plan to `docs/feature-plans/event-driven-alerts_plan.md`.
- Resolve blocking questions and accept prerequisite ADRs.
- Create `feat/event-driven-alerts` from current `dev`, then its task branches.
- Add/update API, schema, test, cost, AI-usage, and runbook documentation.

**Exit gate:** all Definition of Ready items in `project_plan.md` are satisfied.

### Milestone 1 — Parallel Foundations

- Azeez proves the Singapore-to-Tokyo AgentCore call, measures latency and
  cost, and drafts the search/regional and AI ADRs.
- Hayyun freezes the event, holdings-read, alert, feedback, OpenAPI, and
  authorization contracts and builds the Java 25 service/database foundation.
- Both developers agree on interfaces before implementation branches diverge.

**Exit gate:** accepted interfaces and prerequisite ADRs; an isolated search spike
works; deterministic service tests run without AWS or an external provider.

### Milestone 2 — Parallel Vertical-Slice Implementation

- Azeez implements scheduled search, watermarking, deduplication, Luna
  classification/briefing, structured validation, fallbacks, and provider tests.
- Hayyun implements portfolio matching, deterministic exposure/scoring,
  alert APIs, authorization, audit events, feedback, and the initial dashboard.

**Exit gate:** a fixture article can pass through both halves via frozen contracts;
each developer's unit, contract, and failure-path tests pass independently.

### Milestone 3 — Integration and AWS Hardening

- Merge both workstreams into `feat/event-driven-alerts` through reviewed PRs.
- Deploy first to the Singapore development environment with the Tokyo connector
  and Luna invocation disabled, then enable each behind separate controls.
- Run security, authorization, migration, E2E, cross-region outage, replay,
  performance, observability, and cost tests.
- Benchmark AgentCore against the approved fixture set and financial-API fallback
  criteria.

**Exit gate:** one public result produces one authorized, explainable, cited alert
and audited decision; no private field crosses regions; measured cost and quality
meet ADR thresholds. Otherwise, keep the deterministic slice and evaluate the
financial-news API adapter.

### Milestone 4 — Staging and Handover

- Complete runbooks, budget/resource register, AI-usage log, API/schema docs,
  recovery evidence, and task status.
- Promote only through `feat/event-driven-alerts` → `dev` → `staging` after CI,
  review, ADR acceptance, and lower-environment cost/security approval.
- Perform a bounded staging soak and demonstration rehearsal.

**Exit gate:** lower-environment soak and recovery tests pass without duplicate
alerts or uncontrolled search/model calls, and every Definition of Done item is
complete.

### Milestone 5 — Optional Experiments

Evaluate embeddings/vector retrieval or Jev only after the MVP. Promote an
experiment only when a benchmark shows measurable value and its ADR is accepted.

## 19. Task and Ownership Plan

Two developers are assigned: Azeez and Hayyun. Azeez is the confirmed feature
owner. Azeez owns Search/AI/Platform; Hayyun owns CRM/Domain/Experience and leads
final integration with Azeez collaborating.

### 19.1 Ownership Boundary

| Role | Primary ownership | Must not change without coordination |
|---|---|---|
| Azeez — Search/AI/Platform | AgentCore, Bedrock adapter, ingestion, deduplication, scheduled worker, AWS IaC, search/model evaluation, operational metrics | Domain/API contracts, DB migrations, frontend, authorization policy |
| Hayyun — CRM/Domain/Experience | Domain model, migrations, holdings contract, exposure/scoring, REST API, authorization, auditor integration, dashboard, feedback, E2E integration | AgentCore/IAM/IaC, search adapter internals, model prompt/schema |

The proposed service paths below are placeholders until the service-boundary ADR
is accepted. Once accepted, record the actual paths in this table before coding.

### 19.2 Actionable Task Table

| ID | Owner | Deliverable and completion evidence | Expected files/components | Branch | Depends on | Status | Last Sync |
|---|---|---|---|---|---|---|---|
| EDA-00 | Azeez, Hayyun | Confirm feature slot and Azeez as owner; approve canonical plan and create feature branch | Feature plan, ownership record | `task/event-driven-alerts/plan` | Team decision | Complete | 2026-09-24 |
| EDA-D1-01 | Azeez | Singapore→Tokyo AgentCore spike with measured latency, sample filtered results, IAM notes, teardown, and actual cost | Spike code, architecture notes, AI/cost logs | `task/event-driven-alerts/search-spike` | EDA-00 | Ready | 2026-09-24 |
| EDA-D2-01 | Hayyun; Azeez collaborating under feature-owner authorization | Freeze event, holdings-read, alert, feedback, authorization, and OpenAPI contracts with synthetic fixtures | `docs/api/`, `docs/database/`, proposed service contracts/test fixtures | `task/event-driven-alerts/contracts` | EDA-00 | In Progress — proposed ADRs/contracts/fixtures drafted; human acceptance and Hayyun review pending | 2026-09-27 |
| EDA-D1-02 | Azeez | Terraform/IaC for Tokyo Gateway/tool, IAM, Singapore scheduler/ECS worker, quotas, alarms, and cleanup; validated plan with no unapproved resources | `infrastructure/` paths confirmed by infra owner, cost register | `task/event-driven-alerts/search-infra` | EDA-D1-01, accepted ADRs | Blocked | 2026-09-24 |
| EDA-D2-02 | Hayyun | Java 25 alert-service foundation, migrations, repositories, state machine, and deterministic fixture path; unit/migration tests pass | Proposed `services/event-alert-service/`, schema docs | `task/event-driven-alerts/service-foundation` | EDA-D2-01, DB ADR | Blocked | 2026-09-24 |
| EDA-D1-03 | Azeez | Grouped-query builder, date watermark/overlap, URL deduplication, bounded retry, provider interface, and AgentCore adapter; contract/failure tests pass | Search/provider/ingestion/worker packages | `task/event-driven-alerts/ingestion` | EDA-D1-02, EDA-D2-01 | Blocked | 2026-09-24 |
| EDA-D2-03 | Hayyun | Holdings adapter, deterministic exposure/scoring, reproducible rule versioning, and stale/missing valuation behavior; unit/contract tests pass | Domain/scoring/holdings packages | `task/event-driven-alerts/scoring` | EDA-D2-01, EDA-D2-02 | Blocked | 2026-09-24 |
| EDA-D1-04 | Azeez | Luna classifier/brief adapter with schema validation, prompt-injection defenses, public-data enforcement, token caps, rule fallback, and benchmark report | Classification/briefing packages, evaluation fixtures/report | `task/event-driven-alerts/ai` | EDA-D1-03, AI ADR | Blocked | 2026-09-24 |
| EDA-D2-04 | Hayyun | Alert/decision APIs, own-client authorization, admin-policy implementation, audit events, OpenAPI updates, and authorization tests | API/security/audit packages, `docs/api/` | `task/event-driven-alerts/api` | EDA-D2-02, EDA-D2-03 | Blocked | 2026-09-24 |
| EDA-D1-05 | Azeez | CloudWatch metrics/dashboard/alarms, cost counters, kill switches, provider-outage behavior, replay controls, and operations runbook | IaC observability, application metrics, `docs/runbooks/` | `task/event-driven-alerts/operations` | EDA-D1-02 through EDA-D1-04 | Blocked | 2026-09-24 |
| EDA-D2-05 | Hayyun | Dashboard alert list/detail, evidence/citations, explanation factors, feedback actions, loading/error/empty states, and UI tests | Existing frontend paths confirmed with frontend owner | `task/event-driven-alerts/dashboard` | EDA-D2-04, UI contract | Blocked | 2026-09-24 |
| EDA-D2-06 | Hayyun; Azeez collaborates | Integrate both streams; E2E, security, migration, performance, failure/recovery, and staging tests; complete docs and demo evidence | Integration/E2E tests, CI, runbooks, feature plan | `task/event-driven-alerts/integration` | All D1/D2 tasks | Blocked | 2026-09-24 |

### 19.3 Execution Sequence for Two Developers

```text
Both: EDA-00 and contract/interface agreement

Azeez                               Hayyun
-----                               ------
D1-01 Search spike          ||      D2-01 Contracts
D1-02 Search infrastructure ||      D2-02 Service foundation
D1-03 Search ingestion      ||      D2-03 Matching/scoring
D1-04 Luna integration      ||      D2-04 API/security/audit
D1-05 Operations            ||      D2-05 Dashboard
                 \                 /
                  \               /
                   D2-06 Integration
                   (D1 collaborates)
```

Parallel work may begin only after D2-01 contracts are reviewed. Azeez owns
provider-side DTOs/adapters; Hayyun owns domain/API DTOs. Contract changes
after that point require both developers' approval and an update to the feature
plan before code changes.

### 19.4 Per-Task Working Rules

For every task, the assigned developer must:

1. Fetch and synchronize with `origin/feat/event-driven-alerts` before editing.
2. Confirm the ADR/contract dependency is accepted.
3. Change only the owned components or coordinate the conflict in this plan.
4. Add unit and relevant contract/failure tests in the same PR.
5. Update API/schema/runbook/cost/AI-usage documentation where applicable.
6. Record the actual verification command and result in the PR.
7. Re-fetch and synchronize before opening the PR.
8. Merge only into `feat/event-driven-alerts`, never directly into `dev`,
   `staging`, or `main`.

## 20. Deployment, Migration, and Rollback

Deployment follows `task/event-driven-alerts/*` → `feat/event-driven-alerts` →
`dev` → `staging` → `main`, with review and CI at each gate.

- Apply backward-compatible migrations before code that requires them.
- Keep ingestion/model invocation disabled by default behind independent flags or
  quotas.
- Enable first in `dev` with synthetic data, then `staging` after security and cost
  review.
- Verify health, freshness, authorization, audit delivery, alarms, and budgets.
- Roll back by disabling ingestion/model calls first, reverting the application
  second, and retaining compatible data for forward-fix/replay.
- Do not drop tables or columns in the same release that stops using them.
- Document replay, backlog cleanup, and provider-key revocation.

## 21. Acceptance Criteria

The MVP is acceptable only when all are demonstrated with synthetic data:

1. An approved article is ingested exactly once with source provenance.
2. The Singapore lower environment successfully invokes AgentCore Web Search
   `1.2.0` in Tokyo using an enforced domain allowlist, inclusive date range, and
   bounded result count.
3. Request/log inspection proves that search and Luna receive public market data
   only; no client, agent, account, holding, exposure, internal-note, or audit data
   crosses the Singapore boundary.
4. An explicit instrument reference is matched with a visible explanation.
5. Exposure and alert score are reproducible from versioned inputs and rules.
6. A qualifying event creates alerts only for affected clients and assigned agents.
7. Another agent cannot list, view, or decide that alert.
8. The briefing is structured, source-grounded, labelled AI-generated, and omits
   direct client PII and raw account details from model prompts.
9. All three decisions persist with actor/time and never contact a client
   automatically.
10. Generation and decisions produce queryable, tamper-resistant audit records.
11. Tokyo endpoint failure, Luna timeout, invalid output, duplicate delivery,
    stale valuation, and audit failure behave as documented and do not fabricate
    results.
12. Metrics expose search count/quota/cost, cross-region latency, failure,
    fallback, backlog, and model cost without PII.
13. Required unit, contract, integration, authorization, security, E2E, migration,
    performance, and recovery evidence passes.
14. Estimated and measured usage fits the feature allocation under the USD 300
    project limit without consuming protected contingency.
15. ADRs, OpenAPI/schema docs, runbooks, task status, and AI-usage records are
    current, reviewed, and merged.

## 22. Approved MVP Decisions

| Decision | Approved MVP baseline |
|---|---|
| Feature and owner | Feature 4; Azeez is feature owner |
| Delivery ownership | Azeez — Search/AI/Platform; Hayyun — CRM/Domain/Experience and final integration |
| Holdings/valuation | Small synthetic dataset owned by the alert service; timestamped, currency-aware, no live market feed |
| Search | AgentCore Web Search in Tokyo; public terms only, approved domains/dates, 1,000-query monthly quota and kill switch |
| AI model | GPT-5.6 Luna using global cross-region inference; public news only, no CRM/client/private data |
| Initial sources | Regulators, exchanges, and official issuer investor-relations sites; commercial publishers require terms review |
| Runtime | Separate Java 25 Spring Boot Maven module, subject to the detailed service-boundary ADR |
| Processing | EventBridge Scheduler to bounded ECS batch task; no new alert broker; reuse auditor integration |
| Admin access | Read-only oversight across alerts; only assigned agents record Inform/Monitor/Dismiss decisions |
| Jev/semantic retrieval | Excluded from MVP |
| Alert-volume guardrail | Maximum 10 new alerts per agent per day during MVP |
| Retention | News metadata, prompts, and briefs: 90 days; audit events: one year; synthetic data only |
| Budget | USD 25 monthly operating alert; USD 50 feature development hard cap; project USD 300 limit remains authoritative |

These choices are recorded in ADR-0004. Detailed architecture, data, regional,
model, source-governance, API, audit, and operational ADRs remain prerequisite
gates. The AgentCore spike and contract-design tasks may start; production-shaped
implementation remains blocked until their applicable ADRs and contracts are
accepted.

## 23. Current AWS References

- [AgentCore Web Search Tool](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/gateway-target-connector-web-search-tool.html)
- [AgentCore regional availability](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/agentcore-regions.html)
- [AgentCore pricing](https://aws.amazon.com/bedrock/agentcore/pricing/)
- [Native Bedrock Web Search](https://docs.aws.amazon.com/bedrock/latest/userguide/web-search.html)
- [GPT-5.6 Luna model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-openai-gpt-56-luna.html)

AWS service availability, model routing, capabilities, and pricing can change.
Reverify these official references when the ADR is reviewed and immediately before
deployment.

## 24. Definition of Done

In addition to these acceptance criteria, completion requires every repository-
wide Definition of Done item in `project_plan.md`: reviewed code, passing CI,
accepted ADRs, current plan/task status, OpenAPI and schema documentation,
migrations, audit and authorization tests, observability, runbooks, cost review,
successful environment deployment, rollback evidence, synthetic-only data, and no
committed secrets or PII.
