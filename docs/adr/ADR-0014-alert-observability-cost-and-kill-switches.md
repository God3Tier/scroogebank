# ADR-0014: Alert observability, cost controls, and kill switches

- Status: Proposed
- Decision date/time: 2026-09-27 01:54:06 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/contracts`
- Supersedes: None

## Context

The feature has strict query, model, alert-volume, and budget limits and must be
operable without logging private data.

## Decision

Emit structured logs with correlation IDs and codes, never queries, snippets,
prompts, client identifiers, holdings, decisions, tokens, or credentials. Metrics
cover schedule/run health, watermark age, backlog, deduplication, search count and
latency, domain/date-filter failures, model calls/tokens/latency/abstentions,
alerts created/suppressed, outbox age, authorization failures, and estimated cost.
Dimensions use environment/provider/result code only and remain low-cardinality.

Alarms cover missed schedules, stale watermarks, backlog/outbox age, Tokyo/model
failure rate, cross-region p95 latency, monthly search count at 500/800/1,000,
model-token caps, ten-alerts-per-agent/day suppression, USD 25 monthly feature
alert, and USD 50 development cap. Billing alerts remain authoritative; in-app
estimates are advisory.

Separate default-off controls disable ingestion and model invocation. Turning
them off never blocks reading existing alerts or recording decisions. Quota and
budget breaches automatically disable the relevant outbound capability and
require a human-owned audit event to re-enable it.

## Consequences

Operators can stop spend and outbound processing independently while preserving
the CRM workflow. Low-cardinality, redacted telemetry limits debugging detail.

## Validation

Tests exercise each switch, quota boundary, alarm signal, redaction rule, and
degraded mode. A runbook drill must demonstrate disable, inspect, recover, replay,
and re-enable without duplicate alerts.
