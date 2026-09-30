# ADR-0013: Alert audit delivery and failure policy

- Status: Proposed
- Decision date/time: 2026-09-27 01:54:06 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/contracts`
- Supersedes: None

## Context

AI generation and human decisions require durable, tamper-resistant audit facts.
The existing auditor consumes Kafka, but database commits and audit publication
cannot be made atomic across services.

## Decision

Write a versioned audit outbox row in the same PostgreSQL transaction as every
classification result, briefing generation result, alert creation, decision, and
feedback mutation. A bounded publisher sends the existing auditor-compatible
message and marks the row delivered only after acknowledgement. Event IDs make
delivery idempotent.

Audit payloads contain actor subject/role where applicable, action, resource ID,
timestamp, correlation ID, before/after state codes, rule/prompt/model versions,
source references, and outcome. They exclude client names, account numbers, raw
holdings, article bodies, prompts, tokens, and credentials.

Decision and feedback endpoints fail closed with `503` before accepting a new
mutation when the outbox cannot durably persist. If the auditor is temporarily
unavailable after persistence, the mutation succeeds with queued audit status and
the publisher retries with alarms. Alert generation may continue only while the
bounded outbox has capacity; hitting the cap activates the ingestion kill switch.

## Consequences

Audit delivery is at-least-once and consumers must deduplicate. Short auditor
outages do not lose accepted work; an outbox/storage outage blocks mutations.

## Validation

Tests cover transaction rollback, duplicate delivery, reordered acknowledgement,
auditor outage, outbox cap, replay, redaction, and one-year auditor retention.
