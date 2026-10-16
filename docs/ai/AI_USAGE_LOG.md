# AI usage log

## 2026-10-16 — INF-04 transaction-ingest Lambda

- Human owner: Neo Jia Wen; the Feature 3 owner (Joseph Yau) confirms the
  runtime (ADR-0024 decision 7) on the pull request.
- Tool: Claude Code (Anthropic).
- Use: run the SFTP receiver's poller as the Lambda function ADR-0024 describes:
  a Lambda mode in `src/cronjob` that builds the poller once per execution
  environment and polls once per invocation; read the database login, SSH
  private key and Cognito client secret from Secrets Manager at init; quote the
  database login in the connection string; add `make lambda` to build the arm64
  `bootstrap` zip with the RDS CA bundle; in Terraform, point `DB_SSLROOTCERT`
  at that bundle and turn off Lambda's own retries of the scheduled run; update
  the receiver README, the runbook, the Terraform README and the
  infrastructure plan; amend ADR-0042 decisions 9 and 10 to match.
- Human verification required: review the code; run `make test` and
  `make lambda` with Docker; apply to a test account (INF-02) and check one
  invocation imports files and audits them.
- Data: repository content and synthetic test values only. No AWS credentials,
  secrets, real client data or live AWS resources were used.
- Verification: `go vet` and the cronjob, accounts and poller tests passed
  locally, and the arm64 `bootstrap` cross-compiled; the database-backed SQS
  relay tests were not run (no database or Docker). Terraform validate and 6
  CRM mocked-provider tests passed. `make lambda` was not run (Docker was not
  running).

## 2026-10-16 — Terraform review and repair

- Human owner: Neo Jia Wen.
- Tool: OpenAI Codex.
- Use: inspect current remote branches; repair a committed ADR-index merge
  conflict; preserve and mark the abandoned Free-plan design as superseded;
  add reproducible Terraform provider locks; replace long-lived administrator
  access-key guidance with temporary-credential guidance; correct cross-region
  RDS backup encryption; restrict production OAuth callbacks and direct browser
  auth flows; add CloudTrail/frontend TLS controls, CloudTrail versioning and
  redacted WAF request logging; synchronize cost and resource documentation.
- Human verification required: review the new Sydney KMS-key cost and policy,
  approve the proposed ADRs, run Checkov in CI, inspect a saved real-account
  staging plan, and complete INF-02 before any production apply.
- Data: repository content and synthetic configuration only. No AWS credentials,
  secrets, real client data or live AWS resources were used.
- Verification: Terraform 1.16.6 formatting and validation passed for bootstrap,
  CRM and SFTP roots; 2 bootstrap and 6 CRM mocked-provider tests passed.
  Docker was unavailable and the configured secondary Python package index was
  unreachable, so Checkov did not run; no live plan/apply or cost evidence is
  claimed.

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

## 2026-09-30 — EDA-D2-06 offline integration and lifecycle controls

- Human owner: Azeez; Hayyun review remains required for shared domain and
  experience scope.
- Tool: OpenAI Codex.
- Use: integrate the API, ingestion, AI, operations, and dashboard task streams;
  implement the source-to-alert persistence path, operational quota wiring,
  leased Kafka audit publication, strict auditor allowlisting, bounded retention,
  PostgreSQL end-to-end coverage, and desktop/mobile browser verification.
- Human verification required: Hayyun reviews the integrated behavior and the
  proposed ADRs; Azeez reviews the synthetic AI labels before any feature-to-dev
  PR or provider enablement.
- Data: repository content and synthetic fixtures only. No credentials, real
  client data, AWS endpoint, model provider, or production broker was used.
- Verification: focused Java/PostgreSQL tests and the Go auditor contract test
  passed; the full clean Maven/frontend verification is recorded in the offline
  integration evidence after completion. Desktop and mobile synthetic dashboard
  screenshots were inspected locally.

## 2026-09-30 — EDA-D2-06 offline delivery and recovery closure

- Human owner: Azeez; Hayyun review remains required for shared auditor and
  domain behavior.
- Tool: OpenAI Codex.
- Use: add an embedded-Kafka producer/consumer acknowledgement test, verify that
  a failed event does not stop its publisher batch, exercise expired lease
  recovery and the retry cap against PostgreSQL, and add the Go auditor contract
  suite to CI with a disposable healthy database.
- Human verification required: Hayyun reviews the cross-service contract and
  proposed ADRs before feature promotion; the authenticated operator role
  contract remains a human design decision.
- Data: repository content and synthetic UUIDs/payloads only. No credentials,
  real client data, AWS endpoint, production broker, or external model was used.
- Verification: the clean Java reactor, disposable Go/PostgreSQL auditor tests,
  frontend tests/lint/build, Compose validation, and diff checks are recorded in
  the offline integration evidence.

## 2026-09-30 — Holdings-derived public search universe

- Human owner: Azeez; Hayyun review remains required for holdings/domain scope.
- Tool: OpenAI Codex.
- Use: replace the static active-instrument search universe with a Singapore-side
  aggregate derived from synthetic client holdings; map stable instrument IDs to
  public ticker, issuer, and sector terms; add minimum client-count/share privacy
  controls, synthetic reference migration, tests, ADR, and documentation.
- Human verification required: review ADR-0016, the 5%/two-client prototype
  thresholds, selection-inference risk, and the real instrument-master boundary
  before AWS enablement or promotion to `dev`.
- Data: repository-owned synthetic holdings and public synthetic instrument
  metadata only. No credentials, real clients, AWS endpoint, or external model
  was used.
- Verification: focused selection/query tests and the full local verification
  suite are recorded in the offline integration evidence.

## 2026-09-30 — Stakeholder and ASR report documentation

- Human owner: Azeez; team review is required before proposal/final-report use.
- Tool: OpenAI Codex.
- Use: draft a Solution View that introduces referenced AWS services, distinguish
  application users from IAM identities, document stakeholder permissions, and
  write Admin, Agent, and Event-Driven Client Alert ASRs with main/alternative
  flows, pre/post-conditions, architectural significance, and Mermaid sequences.
- Human verification required: confirm the Admin/Agent core-feature behavior with
  the relevant owners; confirm AWS service names and deployed evidence with the
  platform owner; accept the proposed alert ADRs before presenting planned
  controls as implemented.
- Data: repository architecture decisions, plans, NFR notes, and synthetic-system
  descriptions only. No credentials, real client data, AWS connection, external
  model call, or production evidence was used.
- Verification: cross-checked terminology against ADR-0004 and proposed
  ADR-0006 through ADR-0016; checked Markdown structure, Mermaid participants,
  regional privacy boundaries, and explicit separation of current MVP behavior
  from future vector-similarity work.
