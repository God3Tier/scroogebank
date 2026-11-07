# ADR-0012: Alert API, event, and authorization contracts

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-09-27 01:54:06 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/contracts`
- Supersedes: None
- Superseded by: [ADR-0066](ADR-0066-crm-linked-current-alert-holdings.md) in part (current-agent authorization and routing follow the client's current agent in the CRM)

## Context

The API must support agent workflows, admin oversight, frozen service boundaries,
and later UI work without exposing another agent's clients.

## Decision

Publish OpenAPI 3.1 routes under `/api/alerts`. Agents may list/view alerts only
for clients assigned to their authenticated subject and may record one of
`INFORM_CLIENT`, `MONITOR`, or `DISMISS`. Admins have read-only global oversight
and cannot record those decisions. Feedback is non-authoritative quality input
and follows the same ownership rule.

Identity and roles come only from validated Cognito JWT claims, never request
parameters. Repository queries include the authorization scope so inaccessible
records return `404`, preventing enumeration. Decision writes require an
idempotency key and expected version; duplicate keys replay the original response
and stale versions return `409`.

Internal contracts use versioned JSON envelopes with event ID, type, version,
occurred-at time, correlation ID, producer, and payload. Consumers reject unknown
major versions and ignore documented additive fields. No raw account number,
client name, or model prompt is placed on an event.

## Consequences

The UI has stable contracts and authorization is enforced below controllers.
Admin write access would require a new decision and contract version.

## Validation

OpenAPI linting, schema fixtures, agent/admin/other-agent tests, enumeration tests,
idempotency, optimistic concurrency, and compatibility tests are mandatory.
