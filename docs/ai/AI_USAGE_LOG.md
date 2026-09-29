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
