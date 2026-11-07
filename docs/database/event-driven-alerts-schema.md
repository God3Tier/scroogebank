# Event-driven alerts database schema

Status: Accepted (2026-11-01) with ADR-0005 through ADR-0014; the current-portfolio tables follow ADR-0066.
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
| `instrument_search_coverage` | Last successful scheduled-search end and update time per active instrument; drives deterministic four-instrument rotation. Replays never update it. |
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

`V8__seed_global_synthetic_portfolios.sql` adds 15 real public instrument rows to
the existing DBS reference, then upserts 20 opaque synthetic clients/accounts
assigned round-robin to four synthetic agents. The matching deterministic
holdings fixture contains no real PII and spans SGX, NASDAQ, NYSE, LSE, and HKEX.

`V9__instrument_search_metadata.sql` adds JSON-array `search_aliases` and
`official_domains` reference fields to `instrument` and seeds them for the
synthetic global catalogue. They are public, governed query metadata only; no
portfolio facts are stored in either field.

`V10__current_holdings.sql` (ADR-0066) is additive. It adds `current_portfolio`
(CRM `account_id` key, `client_id`, `revision`), `current_position` (positive
quantity/price/FX, derived `value_sgd`, real `valued_at`), `portfolio_write_request`
(per-actor idempotency), `portfolio_snapshot` (the group of positions scored
together: account, revision, agent, total), `client_assignment` (refreshed copy of
the current agent per CRM client; a null agent means no owner),
`agent_event_action` and `agent_event_action_member`. It drops only the
fixture-only foreign key from `holding_snapshot.client_ref` to
`synthetic_portfolio` and adds nullable `portfolio_snapshot_id` and
`portfolio_revision` to `holding_snapshot`. `synthetic_portfolio`, every legacy
`holding_snapshot` and every alert are untouched; legacy detail keeps its original
grouping (client, source version, valuation time), new detail uses the snapshot
group. CRM IDs are references checked through the directory, never foreign keys.

`V12__event_reports.sql` (ADR-0084) is additive. It adds `public_event_source` (every source linked to an event,
backfilled from `public_event`), `event_report` (one versioned report per event, with the source IDs the model saw,
model and prompt versions, and an expiry; existing briefings are copied in as `briefing-v1-legacy` version 1) and
`event_report_attempt` (failed generation attempts). The `briefing` table is kept for rollback. V13 is skipped on
purpose: the search-on-add branch uses V14 and V15.

`V17__news_alert_approvals.sql` (ADR-0085) turns open `MONITORING` alerts into `NEW` and allows `SENT_OUT`. It adds
`event_report.summary_sentence` and `summary_model` (the worker's optional one-sentence summary), `report_edit` (one
agent's unapproved edit of one report version), `approved_report` (the frozen text the client receives, the original
and final hashes and `valid_until`; the text is cleared once every client on it is Sent Out or 14 days after it
expired) and `alert_sent_out` (which email took a client to Sent Out).

Engagement service `V3__news_draft_reports.sql` (ADR-0086) replaces the never-deployed `V3__alert_report_deliveries.sql`.
It changes `email_drafts` constraints only (purpose `NEWS_ALERT`, tone `NONE`, source `NEWS`, all three together) and
adds `email_draft_reports` (the approved reports on a news draft; a partial unique index keeps a client's report on
at most one open or sent draft) and `email_draft_news` (the built summary's hash and whether Sent Out was recorded).
Client addresses are never stored; the report summary lines are cleared when the draft is sent or discarded.

Engagement service `V4__one_open_news_draft_per_client.sql` adds a partial unique index on `email_drafts (owner_agent_sub, client_id)`
where the purpose is `NEWS_ALERT` and the status is `DRAFT` or `SENDING` (one open news draft per client), and
`email_draft_news.sent_out_unmarked` (the alerts refused for good after a send, with the reason).
