# Event-driven alerts database schema

Status: Proposed; implementation requires accepted ADR-0005 through ADR-0014.
The alert service exclusively owns the `event_alert` database and Flyway history.

## Core relationships

```text
search_run 1--* source_result 1--0..1 public_event
public_event *--* instrument (public_event_instrument)
instrument 1--* holding_snapshot *--1 synthetic_portfolio
public_event 1--* alert *--1 holding_snapshot
alert 1--* alert_decision
alert 1--* alert_feedback
alert/public_event mutations 1--* audit_outbox
```

## Tables

| Table | Purpose and important constraints |
|---|---|
| `search_run` | Schedule window, status, lease, counts, and error code. Unique scheduled window; no raw query text. |
| `source_result` | Minimal source metadata, canonical URL/hash, publication/retrieval time, source domain, retention expiry. Unique canonical URL hash. |
| `public_event` | Validated structured public event, classifier outcome/confidence and rule/model/prompt versions. Unique accepted source-result/classifier version. |
| `instrument` | Supported synthetic instrument ID, ticker, issuer, sector, currency, active flag. It maps a holding's stable ID to public search/category terms inside Singapore. |
| `public_event_instrument` | Explicit event/instrument match with match reason and confidence. Composite primary key. |
| `synthetic_portfolio` | Opaque synthetic client/account and assigned-agent references; never real PII. |
| `holding_snapshot` | Immutable quantity/price/FX/value/timestamp/source version and freshness for one portfolio/instrument. |
| `alert` | Client-event result, exposure snapshot reference, score/rule version, state, creation time, optimistic version. Unique client/event/instrument. |
| `alert_factor` | Ordered deterministic score factor code, contribution, and display explanation. |
| `briefing` | Structured AI output, citation references, model/prompt versions, AI label, retention expiry; no CRM PII. |
| `alert_decision` | Immutable agent decision, actor subject, prior/new state, resulting alert version, note, idempotency key, and time. Unique actor/idempotency key. |
| `alert_feedback` | Agent quality rating/reason/comment, actor, observed alert version, idempotency key, and time. Unique actor/idempotency key. |
| `processing_watermark` | Last successful provider window with overlap and optimistic version. |
| `instrument_search_coverage` | Last successful scheduled-search end and update time per active instrument; drives deterministic 32-instrument rotation. Replays never update it. |
| `outbound_usage` | Search count, model tokens and estimated cost per UTC month, shared by the API and every worker run. |
| `outbound_control` | The disabled reason for each outbound capability (`INGESTION`, `MODEL`), if any. |
| `audit_outbox` | Event ID/type/version, redacted JSON payload, attempt/lease state, acknowledgement, and created time. Unique event ID. |

## Data types and controls

- UUID primary keys are application-generated.
- Timestamps are `timestamp with time zone` and stored in UTC.
- Money uses `numeric(19,4)` internally and API responses round explicitly to two
  decimals; quantity and FX use `numeric(24,8)`.
- Score uses `numeric(5,2)` with a 0–100 check; confidence uses `numeric(5,4)`
  with a 0–1 check.
- State, decision, freshness, and result codes use checked text values so database
  migrations can add values without vendor enum replacement.
- URLs are capped at 2048 characters; titles at 300; agent notes/comments at 500.
- Repository queries always include authorized assigned-agent scope unless the
  validated role is admin read-only.

## Retention and recovery

`source_result`, prompts/model outputs, and briefings receive a 90-day expiry.
Bounded cleanup clears content while preserving keys and audit references needed
to explain historical decisions. Audit retention is one year in the auditor, not
implemented by deleting outbox rows before acknowledgement.

Backups follow the approved shared-cluster policy. Restore tests replay pending
outbox rows and processing windows using unique constraints; they must not create
duplicate alerts, decisions, or notifications. Migrations are forward-only and
destructive changes require expand/migrate/contract releases.

`V2__seed_synthetic_instrument_catalogue.sql` adds the synthetic-only reference
instrument used by the fixture. It contains no client, account, holding, or agent
data and must be replaced by an approved instrument-master integration before
real holdings are introduced.

`V7__instrument_search_coverage.sql` adds only the per-instrument coverage table.
It starts empty, so every eligible instrument initially uses the normal four-hour
lookback. Failed, quota-blocked and replay runs leave it unchanged.
