# Event-driven alerts internal contracts

Status: Proposed and frozen for implementation only after ADR-0005 through
ADR-0014 are accepted. Owners: Hayyun (primary) and Azeez (collaborating under
the feature owner's 2026-09-27 instruction).

## Contract rules

- JSON uses UTF-8, camelCase fields, UUID identifiers, ISO-8601 UTC timestamps,
  and decimal strings for money.
- Envelopes have a semantic major version. Unknown major versions are rejected;
  consumers ignore unknown additive fields within a known major version.
- All IDs and examples are synthetic. Public-provider payloads never contain
  client/account/holding/agent/audit data.
- Logs identify correlation and event IDs only, not payload values.

## Envelope

```json
{
  "eventId": "10000000-0000-4000-8000-000000000001",
  "eventType": "public-market-event.detected",
  "eventVersion": 1,
  "occurredAt": "2026-09-27T00:00:00Z",
  "correlationId": "20000000-0000-4000-8000-000000000001",
  "producer": "event-alert-service",
  "payload": {}
}
```

## Public market event v1

The provider-side payload ends at public data. Required fields are event ID,
headline, event type, direction, confidence, affected instrument IDs, evidence
citations, classifier/model version, and prompt version. Confidence is 0 to 1.
Event types are `EARNINGS`, `REGULATORY`, `CORPORATE_ACTION`, `CREDIT`,
`LEADERSHIP`, `MACRO`, or `OTHER`; direction is `POSITIVE`, `NEGATIVE`, `MIXED`,
or `UNCERTAIN`. An abstention is stored separately and never emitted as a detected
event.

## Holdings read v1

Input contains a set of supported instrument IDs and an `asOf` timestamp. Output
contains synthetic client/account references and immutable position valuations:
instrument, quantity, unit price, valuation currency, FX-to-SGD, SGD value,
valuation timestamp, freshness, and source version. Missing and expired values
are explicit result entries, not zeroes.

The adapter is read-only. It cannot return client names, contact details, bank
account numbers, notes, or authentication attributes.

## Alert-created v1

The internal event includes alert ID, synthetic client reference, assigned-agent
subject, public event ID, instrument ID, score, rule version, valuation snapshot
ID, status, and creation timestamp. It excludes raw holding values and briefing
text; consumers retrieve authorized detail through the REST API.

## Decision-recorded v1

The event includes decision ID, alert ID, actor subject and role, decision code,
previous/new status, alert version, correlation ID, and timestamp. It never means
that the client was contacted; `INFORM_CLIENT` records the agent's chosen next
step only.

## Audit mapping

Every classification/brief outcome, alert creation, decision, and feedback write
maps to one outbox event ID. Audit payloads use resource IDs, state codes, version
identifiers, source references, outcome, and actor claims where applicable. They
exclude raw provider/model content and private financial values.

## Compatibility fixtures

Canonical synthetic fixtures live in `tests/fixtures/event-driven-alerts/` and
must be validated by producer and consumer tests. Changing a required field,
meaning, enum removal, or type requires a new major version and both owners'
approval. Adding an optional field is backward-compatible.
