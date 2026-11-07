# ADR-0014: Alert observability, cost controls, and kill switches

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-09-27 01:54:06 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/contracts`
- Supersedes: None
- Superseded by: [ADR-0050](ADR-0050-event-search-provider-after-scp-block.md) in part (the search alarms, quota and kill switch apply to the Tavily search, not a Tokyo AgentCore Gateway)

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

## Amendment (2026-10-20): control state is kept in alerts-db

Proposed by Hayyun for the owner's review; this ADR is still Proposed, so it
changes no accepted decision.

The API and the run-once worker (ADR-0031, ADR-0007) are separate processes, and
the worker is a new one every four hours. Control state held in memory is lost
between runs, so a monthly quota, the cost stop or an operator's disable made in
one process never reached the next. The state is therefore kept in alerts-db:

- `outbound_usage`: searches, model tokens and estimated cost per UTC month.
- `outbound_control`: the disabled reason for each capability (`INGESTION`,
  `MODEL`).

A reservation is one atomic `UPDATE ... WHERE total + requested <= quota` in its
own transaction, so concurrent processes cannot exceed a quota and a reservation
survives a rollback of the caller's transaction (the outbound call may already
have happened). A quota or budget stop and an operator disable are written once
and apply to every process until a human re-enables the capability with an audit
event, as decided above. The enable flags stay configuration and always win over
stored state. Estimated cost remains advisory; billing alerts stay authoritative.

Run exclusion is separate from quotas: each ingestion run holds a PostgreSQL
session advisory lock for its whole duration, and a run that cannot take it ends
as `ALREADY_RUNNING` before reserving quota or searching. Scheduler double
delivery or a manual run during a scheduled one therefore cannot double the
searches.

Trade-off: every reservation is a database round trip and the controls now depend
on alerts-db being reachable. That is acceptable because an unreachable database
already stops the run, and a control that cannot be read fails closed.

## Consequences

Operators can stop spend and outbound processing independently while preserving
the CRM workflow. Low-cardinality, redacted telemetry limits debugging detail.

## Validation

Tests exercise each switch, quota boundary, alarm signal, redaction rule, and
degraded mode. A runbook drill must demonstrate disable, inspect, recover, replay,
and re-enable without duplicate alerts.

## Revision history

- 2026-10-20: amendment above, before acceptance: control state moved from process memory to alerts-db and ingestion runs made mutually exclusive with an advisory lock (gaps 3 and 4 in `docs/feature-plans/event-driven-alerts_gaps.md`). Awaiting the owner's review.
