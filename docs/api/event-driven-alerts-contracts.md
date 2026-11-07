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
| GET | `/api/alerts/events?onlyOpen&includeClosed&limit&cursor` | agent | One card per public event (events whose clients are all Sent Out or Dismissed are left out unless `includeClosed`): shared summary, evidence, `affectedClientCount`, `openClientCount`. Returns an opaque `nextCursor` for the next page. Each page rechecks current client ownership. |
| GET | `/api/alerts/events/{eventId}` | agent | The card, the agent's affected clients (alert ID, client, scoring account, instrument, score, share of portfolio, status, version) and the latest action. |
| POST | `/api/alerts/events/{eventId}/actions` | agent | `{decision: INFORM_CLIENT\|DISMISS, note, members[1..200]:{alertId, expectedVersion}}` with `Idempotency-Key`. Applies only to the listed clients and returns a per-member outcome. Recording the status a client already has changes nothing; a Sent Out client is `INVALID_TRANSITION`. |

PUT refusals: 409 stale `expectedRevision`, key reuse with a different body, or a
non-active/non-investment account; 422 unknown instrument, duplicate instrument,
non-positive or over-precise number, future `valuedAt`, value out of range; 404
account missing or not the caller's; 503 CRM unavailable or audit backlog full.

Event action `outcome` is one of `APPLIED`, `STALE_VERSION`, `INVALID_TRANSITION`,
`NOT_AUTHORISED`. A rejected member changes nothing; every outcome is stored in
`agent_event_action_member` and one `alert.event-action-recorded` audit event
(`clientContacted: false`) is written with the action. `INFORM_CLIENT` records
intent only. Replaying the same key and body returns the stored result.

### Search sources (ADR-0079)

| Method | Path | Roles | Contract |
|---|---|---|---|
| GET | `/api/alerts/search-websites` | agent, admin | `shared` (the deployment's news sites), `added[{id, domain, addedAt, addedByYou}]`, `canAdd`, `max`. Searched for every company (ADR-0082). |
| POST | `/api/alerts/search-websites` | agent | `{domain}`. 201 with the entry and `search` (what happened to the search started for the agent's companies, ADR-0081). 400 `INVALID_FORMAT` or `BLOCKED_DOMAIN`; 409 `ALREADY_COVERED` or `LIMIT_REACHED`. Audited as `alert.search-website-added`. |
| DELETE | `/api/alerts/search-websites/{websiteId}` | agent, admin | 204. Keeps the row as history. Audited as `alert.search-website-removed`. |
| GET | `/api/alerts/search-holdings` | agent, admin | Each company held by the agent's clients (admin: everyone's) with `officialDomains` and `holders[{clientId, accountId, quantity, valueSgd, sharePercent, ownerAgentSubject}]`. Client ids are anonymous; the page looks names up in the CRM. The owner is given to admins only. |
| POST | `/api/alerts/search-now` | agent, admin | Optional `{instrumentId}`. Searches one company or every company the caller may search, over 14 days on every website. Returns `{outcome, started[], cooldown[{instrumentId, availableAt}], lookbackDays}`. 403 `NOT_HELD`. Never changes the automatic schedule (ADR-0081). |
| GET | `/api/alerts/search-schedule` | agent, admin | `{enabled, intervalMinutes, zone, previousRunAt, nextRunAt}`. |

Refusals are RFC 9457 problems with an extra `code`. The worker reads active rows from `instrument_source_domain` on every run and adds them to the company's approved domains; they are not official domains, so the strict relevance gate applies in full.

### CRM directory contract (consumed; owned by account-service)

The routes above are in `docs/api/openapi.yaml`, checked against the controllers by the contract tests (ADR-0063).
The alert service reads the CRM through one private route, defined in `docs/api/accounts-api.yaml`:
`POST /internal/alerts/accounts/lookup`, body `{"ids": [<=200 distinct UUIDs]}`, client-credentials token with only
the scope `crm-accounts/alert-directory`. Response `{"items": [{id, client_id, type, status, currency,
owner_agent_id}]}`; unknown and deleted accounts are omitted; `type` and `status` are as stored (lower case) and
read case-insensitively. `owner_agent_id` is the client's current agent: the CRM moves it with the client on
reassignment (ADR-0060). The transaction import's token cannot call this route, and this token cannot call the
import's.

## News alerts: edit, approve, then send from Follow-up Emails (ADR-0085, ADR-0086)

`GET /api/alerts/events/{eventId}` carries `report`: the latest validated `alert-client-report-v2` (or a
migrated `briefing-v1-legacy`), every source linked to the event with the ones the model saw marked
`usedInReport`, the fixed portfolio line per affected instrument, and the disclaimer. A client's status is
`NEW`, `INFORMED` (Plan client briefing), `DISMISSED` or `SENT_OUT` (final). `MONITOR`/`MONITORING` are history
only; a new `MONITOR` decision is a 400.

Event Alerts (agent token, event-alert-service):

| Method | Path | Behaviour |
|---|---|---|
| GET | `/api/alerts/events/{eventId}/report/review` | The original sections, the agent's saved edit, the agent's approval of the latest version and its approved text. |
| PUT | `/api/alerts/events/{eventId}/report/edit` | `{expectedReportVersion, sections}`. Rewords the summary, key facts, why it matters, what to monitor and uncertainty; headline, sources and citations are fixed. Checked again; a failure is a 422 with `code`, `section` and `index`. 409 if the version changed or the report is approved. |
| DELETE | `/api/alerts/events/{eventId}/report/edit` | Throws the unapproved edit away. |
| POST | `/api/alerts/events/{eventId}/report/approval` | `{expectedReportVersion}`. Final. Needs a client in Plan client briefing. Valid for 7 days; an expired approval can be renewed. |
| GET | `/api/alerts/events/{eventId}/report/pdf?version=` | One report as the server-rendered PDF; agents and admins; no approval needed ("Not yet reviewed"). |
| GET | `/api/alerts/sent-out` | The Sent out view: the agent's clients, or everyone's for an admin. |

engagement-service, with the agent's token:

| Method | Path | Behaviour |
|---|---|---|
| GET | `/api/alerts/approved-reports?clientRef=` | The caller's approved, unsent reports in Plan client briefing (the News Alert entry). |
| POST | `/api/alerts/approved-reports/package` | `{eventId, alertIds}`. Per alert `OK` (approval ID, client, issuer, headline, news date, valid until, final hash, summary line) or `NOT_AUTHORISED`, `NOT_INFORMED`, `NOT_APPROVED`. |
| POST | `/api/alerts/approved-reports/pdf` | `{approvalIds, preparedBy}`. The combined PDF (base64), its hash, each report's final hash and each company's start page. No client data; byte-for-byte the same for the same approvals. |
| POST | `/api/alerts/send-check` | `{alertIds}`. Per alert `OK` with the current approval and hash, or `NOT_AUTHORISED`, `NOT_INFORMED`, `NOT_APPROVED`, `EXPIRED`. |
| POST | `/api/alerts/sent-out` | `{alertIds, draftId, sesMessageId}`. Moves the clients to `SENT_OUT`. Idempotent. |

Follow-up Emails (engagement-service, `docs/api/engagement-api.yaml`): `POST /api/engagement/drafts/news`
makes or extends each chosen client's News Alert draft from the package; `DELETE .../drafts/{id}/reports/{alertId}`,
`POST .../summary/rebuild`, `GET .../pdf`, `POST .../news-status` and `GET .../drafts/news/open-alerts`. Sending a
News Alert draft runs the send-check, fetches the combined PDF, sends one email with it attached, then calls
sent-out. The alert follow-up of ADR-0068 (`GET /api/alerts/{alertId}` for `clientRef`) is removed, and so is
ADR-0084's `report/delivery-package` and `alert-report-deliveries`.
