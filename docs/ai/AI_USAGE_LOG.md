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
- Verification: 29 non-container event-alert tests passed. PostgreSQL-backed
  repository and mutation tests remain to be rerun when Docker is available.
