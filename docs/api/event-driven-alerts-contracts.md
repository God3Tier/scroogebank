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
- REST mutations require an `Idempotency-Key` and accept an optional UUID
  `X-Correlation-Id`. The service generates a correlation ID when it is absent.
- Decision and feedback writes fail closed with `503` and `Retry-After: 5` when
  durable audit persistence is unavailable or its bounded backlog is full.

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

Input contains a set of supported instrument IDs, an `asOf` timestamp, and the
assigned-agent subject used to scope the read. Output contains only that agent's
synthetic client/account references and immutable position valuations:
instrument, quantity, unit price, valuation currency, FX-to-SGD, SGD value,
valuation timestamp, freshness, and source version. Missing and expired values
are explicit result entries, not zeroes.

The adapter is read-only. A requested instrument outside the agent's assigned
portfolio is reported as unavailable rather than leaking another agent's
position. It cannot return client names, contact details, bank account numbers,
notes, or authentication attributes.

## Alert-created v1

The internal event includes alert ID, synthetic client reference, assigned-agent
subject, public event ID, instrument ID, score, rule version, valuation snapshot
ID, status, and creation timestamp. It excludes raw holding values and briefing
text; consumers retrieve authorized detail through the REST API.

## Decision-recorded v1

The event includes decision ID, alert ID, actor subject and role, decision code,
previous/new status, alert version, correlation ID, and timestamp. It never means
that the client was contacted; `INFORM_CLIENT` records the agent's chosen next
step only. REST decision responses expose the same `previousStatus` and
`newStatus` state codes so clients do not infer transitions independently.

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

## Current holdings and agent-event routes (ADR-0066)

Present only when `ALERT_HOLDINGS_MODE=database`. Authentication, problem-detail
bodies and idempotency match the alert routes above.

| Method | Path | Who | Behaviour |
|---|---|---|---|
| GET | `/api/alert-portfolios/{accountId}` | the client's current agent; admin read-only | Complete recorded portfolio and `revision`. 404 when nothing was recorded or the account is not visible (indistinguishable). An explicitly recorded empty portfolio returns `positions: []` with its revision. |
| PUT | `/api/alert-portfolios/{accountId}` | the client's current agent | Atomic complete replacement. Body `{expectedRevision, sourceLabel, positions[<=100]}`; each position `{instrumentId, quantity, unitPrice, valuationCurrency, fxToSgd, valuedAt}`. `Idempotency-Key` required. The client and agent are never taken from the body. |
| GET | `/api/alerts/events?onlyOpen&limit&cursor` | agent | One card per public event: shared summary, evidence, `affectedClientCount`, `openClientCount`. Returns an opaque `nextCursor` for the next page. Each page rechecks current client ownership. |
| GET | `/api/alerts/events/{eventId}` | agent | The card, the agent's affected clients (alert ID, client, scoring account, instrument, score, share of portfolio, status, version) and the latest action. |
| POST | `/api/alerts/events/{eventId}/actions` | agent | `{decision: INFORM_CLIENT\|MONITOR\|DISMISS, note, members[1..200]:{alertId, expectedVersion}}` with `Idempotency-Key`. Applies only to the listed clients and returns a per-member outcome. |

PUT refusals: 409 stale `expectedRevision`, key reuse with a different body, or a
non-active/non-investment account; 422 unknown instrument, duplicate instrument,
non-positive or over-precise number, future `valuedAt`, value out of range; 404
account missing or not the caller's; 503 CRM unavailable or audit backlog full.

Event action `outcome` is one of `APPLIED`, `STALE_VERSION`, `INVALID_TRANSITION`,
`NOT_AUTHORISED`. A rejected member changes nothing; every outcome is stored in
`agent_event_action_member` and one `alert.event-action-recorded` audit event
(`clientContacted: false`) is written with the action. `INFORM_CLIENT` records
intent only. Replaying the same key and body returns the stored result.

### CRM directory contract (consumed; owned by account-service)

The routes above are in `docs/api/openapi.yaml`, checked against the controllers by the contract tests (ADR-0063).
The alert service reads the CRM through one private route, defined in `docs/api/accounts-api.yaml`:
`POST /internal/alerts/accounts/lookup`, body `{"ids": [<=200 distinct UUIDs]}`, client-credentials token with only
the scope `crm-accounts/alert-directory`. Response `{"items": [{id, client_id, type, status, currency,
owner_agent_id}]}`; unknown and deleted accounts are omitted; `type` and `status` are as stored (lower case) and
read case-insensitively. `owner_agent_id` is the client's current agent: the CRM moves it with the client on
reassignment (ADR-0060). The transaction import's token cannot call this route, and this token cannot call the
import's.

## Follow-up emails (engagement-service, ADR-0068)

After an agent chooses Inform clients, the event briefing links each informed
client whose `clientRef` is a CRM client ID to a follow-up email in
engagement-service. engagement-service reads `GET /api/alerts/{alertId}`
through the internal ALB with the agent's own token, to check that the alert
is the agent's and its `clientRef` equals the email's client
([ADR-0068](../adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md)).
It reads `clientRef` only and treats 401, 403 and 404 as "not yours". The
headline, holdings and briefing are never read, stored or sent to a model.
Inform still sends nothing: the agent writes and sends the email themselves.
