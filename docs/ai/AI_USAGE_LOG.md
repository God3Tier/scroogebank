# AI usage log

## 2026-09-27 — EDA-D1-01 AgentCore Web Search spike

- Human owner: Azeez
- Tool: OpenAI Codex
- Use: repository/branch inspection, official AWS documentation verification,
  disposable spike-harness implementation, tests, IAM/teardown notes, cost record,
  and feature-plan status update.
- Human verification required: review the code and policies; supply approved
  short-lived AWS development credentials; run only in the lower environment;
  inspect requests/results; verify billing data and teardown; accept any later ADR
  before production-shaped work.
- Data: repository content and synthetic public-market example terms only. No
  secrets, real client data, or confidential bank data were supplied.
- Limitation: the configured AWS credentials were invalid, so Codex did not create
  resources or claim live latency, search quality, or billed-cost evidence.

## 2026-09-27 — EDA-D2-01/D2-02 contracts and service foundation

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: contract and schema drafting, Java 25 Spring Boot service scaffolding,
  Flyway migration design, JPA repositories, lifecycle rules, synthetic fixture
  loading, PostgreSQL Testcontainers tests, documentation, and Git integration.
- Human verification required: Hayyun reviews the domain/API ownership changes and
  both developers accept the proposed ADRs before promotion to `dev`.
- Data: repository content and synthetic identifiers/holdings only. No secrets,
  real client data, confidential bank data, or AWS services were used.
- Verification: local Maven build and seven service tests passed against a
  disposable PostgreSQL 16 Testcontainer.

## 2026-09-27 — EDA-D2-03 holdings and deterministic scoring

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: synthetic agent-scoped holdings adapter, freshness policy, fixed-decimal
  exposure calculations, versioned materiality scoring, failure-state design,
  tests, documentation, and proposed ADR-0015.
- Human verification required: review and calibrate prototype weights/thresholds,
  confirm agent-scope behavior, and accept ADR-0005/ADR-0015 before promotion to
  `dev`.
- Data: repository content and synthetic positions only. No secrets, real client
  data, external providers, or AWS services were used.
- Verification: 21 local event-alert tests passed, including agent isolation,
  freshness boundaries, multi-currency math, replay equality, stale penalties,
  and fail-closed cases.

## 2026-09-27 — EDA-D2-04 alert mutation API and authorization

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: decision/feedback REST mutations, JWT role mapping, assigned-agent
  ownership enforcement, optimistic concurrency, idempotency, redacted audit
  outbox writes, failure responses, tests, and contract documentation.
- Human verification required: Hayyun reviews the API/security behavior and both
  developers accept the proposed authorization/audit ADRs before promotion to
  `dev`.
- Data: repository content and synthetic identities/alerts only. No secrets,
  real client data, external providers, or AWS services were used.
- Verification: the full Maven build passed with 39 event-alert tests, including
  Flyway migration, JPA schema validation, repository joins/authorization, and
  mutation transactions against disposable PostgreSQL 16 Testcontainers.

## 2026-09-30 — EDA-D2-04 explicit decision-state contract

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: exposed the persisted previous and new alert states in decision responses,
  synchronized the OpenAPI and narrative contracts, and extended the controller
  contract test so clients do not need to infer lifecycle transitions.
- Human verification required: Hayyun reviews the API behavior and both
  developers accept the proposed authorization/audit ADRs before promotion to
  `dev`.
- Data: repository content and synthetic identities/alerts only. No secrets,
  real client data, external providers, or AWS services were used.
- Verification: the full 92-test Maven reactor build passed with no failures or
  errors, including disposable PostgreSQL 16 Testcontainers coverage.

## 2026-09-28 — EDA-D1-03 offline ingestion core

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: provider-neutral public-search contracts, grouped-query controls,
  watermark and lease persistence, canonical URL governance and deduplication,
  bounded retries, durable partial-run replay accounting, AgentCore adapter
  boundary, worker-mode guard, tests, and documentation.
- Human verification required: review the provider contract and source controls;
  accept the proposed processing/search/content-governance ADRs; supply the
  separately approved IAM/Gateway implementation only after EDA-D1-02 clears.
- Data: repository content and synthetic public-market terms only. No credentials,
  client data, external provider calls, or AWS services were used.
- Verification: the full Maven build passed with 88 tests, including retry,
  deduplication, durable replay accounting, and PostgreSQL 16 Testcontainers
  schema validation. Live AgentCore behavior, latency, cost, and IAM enforcement
  are not claimed.

## 2026-09-30 — EDA-D1-03 bounded ingestion replay

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: explicit historical replay windows, worker-mode isolation, future/span
  validation, idempotent run reuse, durable result accounting, watermark safety,
  tests, and documentation.
- Human verification required: review the replay span and connect this internal
  boundary only through the future authenticated, audited operator workflow.
- Data: repository content and synthetic public-market terms only. No secrets,
  real client data, provider calls, or AWS services were used.
- Verification: the full Maven build passed with 92 tests, including bounded,
  future-window, worker-mode, durable-count, and watermark-regression cases plus
  PostgreSQL 16 Testcontainers validation.

## 2026-09-29 — EDA-D1-04 offline AI safety layer

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: public-only classifier and briefing contracts, model kill switch,
  prompt-injection/private-field screening, strict structured-output validation,
  citation/instrument enforcement, retry/timeout/token caps, deterministic
  rule-review fallback, synthetic evaluation fixture, metric harness, tests, and
  documentation.
- Human verification required: accept ADR-0010, review safety patterns and
  candidate benchmark gates, expand and label the held-out fixture set, and run
  the live benchmark only through an approved provider connection.
- Data: repository content and synthetic public-market examples only. No secrets,
  real client data, model provider calls, or AWS services were used.
- Limitation: offline tests verify contracts and failure behavior, not Luna model
  quality, latency, tokens, cost, regional routing, or provider availability.

## 2026-09-29 — EDA-D1-04 Bedrock Runtime adapter

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: official-contract review, pinned AWS SDK integration, default-off Bedrock
  Runtime Converse adapter, global Luna inference-profile configuration, bounded
  timeouts/tokens, fail-closed model allowlisting, safe provider-error mapping,
  mocked tests, and documentation.
- Human verification required: accept ADR-0010, review the IAM policy and global
  cross-region data boundary, then run the held-out benchmark only through the
  approved lower-environment connection.
- Data: repository content and synthetic public JSON only. No credentials, real
  client data, model requests, or AWS service calls were used.
- Verification: mocked adapter tests exercise request construction, disabled and
  unapproved-model rejection, timeout propagation, and retryable throttling. Live
  IAM, routing, quality, latency, tokens, and cost remain unverified.

## 2026-09-29 — EDA-D1-04 offline benchmark reporting

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: provider usage-metadata contract, Bedrock token/latency/stop-reason capture,
  strict benchmark-run schema, complete fixture coverage checks, p95 latency and
  token aggregation, candidate quality/safety gates, JSON report entry point,
  tests, and evaluation documentation.
- Human verification required: review fixture labels and candidate thresholds,
  record the approved model price used for estimated cost, inspect every safety
  result, and accept ADR-0010 before enabling the provider.
- Data: repository content and synthetic benchmark observations only. No model,
  AWS, credential, or private-data access occurred.
- Verification: focused tests cover metadata capture, passing/failing gates,
  complete-run enforcement, version propagation, cost recording, and p95 latency.
  No live benchmark result is claimed.

## 2026-09-29 — EDA-D1-04 synthetic fixture expansion

- Human owner: Azeez; Hayyun review remains required before labels are accepted.
- Tool: OpenAI Codex.
- Use: expand the public-only evaluation fixture from five to eighteen synthetic
  cases, add full event-type and negative/safety coverage, enforce unique IDs and
  synthetic identifiers, verify expected safety-screen outcomes, and prevent an
  AI-draft fixture from qualifying as model-enablement evidence.
- Human verification required: Azeez and Hayyun inspect every expected event type,
  negative label, and safety outcome before changing `labelStatus` to
  `HUMAN_REVIEWED` or using results for an enablement decision.
- Data: invented issuer/news examples and synthetic identifiers only. No real
  market article, client data, credentials, model call, or AWS service was used.
- Verification: 54 non-Docker service tests, including fixture-integrity and
  report-eligibility tests, pass locally. The full rerun could not start its two
  PostgreSQL Testcontainers classes because Docker Desktop was unavailable; the
  preceding full build passed 74 tests before this fixture-only change. No model
  quality, latency, routing, token, or cost result is claimed.

## 2026-09-29 — EDA-D1-05 offline operations controls

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: provider-neutral default-off kill switches, hard search/token/budget
  limits, low-cardinality metrics, durable redacted audit-outbox persistence for
  re-enable, cost-control documentation, and an offline operations runbook.
- Human verification required: accept ADR-0014, review limits and planning cost
  assumptions, integrate provider/operator/audit adapters, and validate AWS
  billing, IaC, alarms, and an operations drill before cloud enablement.
- Data: repository content and synthetic test values only. No secrets, real
  client data, external providers, or AWS services were used.
- Verification: the full Maven build passed with 72 tests, including PostgreSQL
  Testcontainers coverage for durable re-enable audit and outbox-capacity failure.

## 2026-09-28 — EDA-D2-05 event-alert dashboard

- Human owner: Azeez; Hayyun review remains required for the CRM/frontend scope.
- Tool: OpenAI Codex.
- Use: active frontend discovery, React alert queue/detail implementation,
  evidence and explanation presentation, agent decisions and feedback,
  read-only administrator oversight, responsive state design, API adapter,
  synthetic fixtures, tests, and documentation.
- Human verification required: Hayyun reviews the frontend integration and a
  developer completes the visual desktop/mobile pass before promotion to
  `feat/event-driven-alerts`.
- Data: repository content and synthetic client, exposure, alert, and public
  citation examples only. No secrets, real client data, external providers, or
  AWS services were used.
- Verification: frontend unit/interaction tests, ESLint, production build, and
  static UI-pattern audit were run locally. Automated screenshot review was not
  available because no controllable browser was exposed to the session.

## 2026-09-30 — EDA-D2-05 dashboard workflow hardening

- Human owner: Azeez; Hayyun review remains required for the CRM/frontend scope.
- Tool: OpenAI Codex.
- Use: latest-decision presentation, immediate status synchronization after a
  mutation, terminal-state decision guards, persistent feedback access, styling,
  tests, and documentation.
- Human verification required: Hayyun reviews the workflow and a developer
  completes the desktop/mobile visual pass before feature-branch integration.
- Data: repository content and synthetic alert/decision values only. No secrets,
  real client data, external services, or AWS resources were used.
- Verification: nine frontend unit/interaction tests, ESLint, and the production
  Vite build passed. Automated screenshots remain pending because the local
  headless browser could not establish its Chrome CDP channel after diagnostics
  and restart.

## 2026-09-30 — EDA-D2-05 authoritative decision-state handling

- Human owner: Azeez; Hayyun review remains required for the CRM experience.
- Tool: OpenAI Codex.
- Use: aligned the synthetic decision adapter with the expanded API response and
  changed the dashboard to render the audited server-supplied `newStatus` rather
  than independently reconstructing the lifecycle transition.
- Human verification required: Hayyun reviews the interaction and completes the
  desktop/mobile visual pass before promotion to `dev`.
- Data: repository content and synthetic alerts only. No secrets, real client
  data, external providers, or AWS services were used.
- Verification: all nine frontend tests passed, ESLint completed without errors,
  and the Vite production build succeeded.
