# ADR-0009: AgentCore search region, governance, and fallback

- Status: Proposed
- Decision date/time: 2026-09-27 01:54:06 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/contracts`
- Supersedes: None

## Context

AgentCore Web Search is available in Tokyo but not Singapore. Cross-region use
must preserve the public/private boundary, control cost, and fail safely.

## Decision

The Singapore worker invokes one IAM-authorized AgentCore Gateway in Tokyo with
the Web Search connector pinned to `1.2.0`. The target enforces an administrator
domain include list; requests may only narrow it. Requests contain public issuer,
ticker, sector, event terms, inclusive UTC dates, and bounded result count—never
CRM identifiers, holdings, exposure, notes, agents, or audit data.

Initial domains are `mas.gov.sg`, `sgx.com`, and individually reviewed official
issuer investor-relations domains. Commercial publishers are disabled pending a
terms review. The application allows at most 1,000 searches/month, four grouped
queries/run, ten results/query, and has an independent ingestion kill switch.

Tokyo timeout or invalid output ends the affected search without fabricated
results. Existing alerts remain readable. There is no automatic fallback provider
in the MVP; a financial-news API requires benchmark evidence and a later ADR.

## Consequences

Public queries cross from Singapore to Tokyo and availability depends on that
Region. The design avoids private-data egress and uncontrolled provider switching.

## Validation

The EDA-D1-01 spike must measure Singapore-to-Tokyo latency, reliability, result
quality, request contents, actual billed cost, and teardown. IAM tests must prove
the caller cannot expand domains or invoke unrelated tools.
