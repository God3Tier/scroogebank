# ADR-0084: Full-coverage search and client event reports

- Status: Proposed (2026-11-05; review pending)
- Decision date/time: 2026-11-05 (Asia/Singapore)
- Decision owner(s): Hayyun (proposer and owner); reviewers: Azeez (author of the plan), Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/event-driven-alerts`
- Related issue/PR: TBD
- Supersedes: the four-query, two-instrument limits of [ADR-0051](ADR-0051-single-issuer-search-and-relevance-gate.md) decision 3 and the per-alert briefing of [ADR-0049](ADR-0049-alert-source-to-alert-pipeline.md) once part 2 lands; the quota, kill-switch and eligibility rules of ADR-0050 and ADR-0051 stand
- Superseded by: N/A

## Context

A scheduled run searches at most four queries, which is two instruments, and rotates through the rest, so with many eligible instruments an instrument can wait a day or more for its turn. Each accepted article also becomes its own event and its own per-client briefing, so one real event reported by three outlets reaches an agent as three events. Agents then have nothing they can send a client: the briefing is for the agent.

This ADR records the plan written by Azeez: search every eligible instrument on every run, combine corroborating sources into one report per event, and deliver individually addressed reports through the existing engagement service only after the assigned agent approves.

## Decision Drivers

- Every instrument that passes the eligibility rules is searched on every run, not in turn.
- The monthly Tavily quota and the USD 25 budget must still hold, and a run they cannot cover must search nothing.
- One real-world event is one thing to the agent, however many sources report it.
- Nothing is sent to a client without an agent choosing the recipients and approving the report.
- No client, account or holding data reaches the model.

## Decision

### Part 1: search and event processing

1. **Every eligible instrument, every run.** The two-instrument rotation and the four-query cap are removed. The eligibility rules are unchanged: held by at least two clients and 20% of clients, or at least 50% of one account (ADR-0051, ADR-0066).
2. **Exactly two searches per instrument**, one per query group. Ten eligible instruments are twenty searches a run.
   - Performance and credit: earnings, results, profit, revenue, guidance, dividend, credit rating, downgrade, upgrade, default, credit outlook.
   - Corporate and external events: merger, acquisition, takeover, divestment, buyback, regulatory action, investigation, fine, filing, approval, appointment, resignation, steps down.
3. **Domains do not add searches.** Official, shared and agent-added domains stay in each request. Domain, date and result validation, URL deduplication and the five-minute coverage overlap are unchanged.
4. **One atomic reservation per run.** The run's whole number of searches is reserved together. If the monthly quota, or the monthly budget at an estimated cost per search (`alert.operations.estimated-search-cost-usd`, default 0.008), cannot cover it, nothing is searched and the run returns CONTROL_BLOCKED. Provider keys are never rotated to get round a limit.
5. **A test setting.** `ALERT_SEARCH_ONLY_INSTRUMENT_ID`, when set, searches only that instrument. It must be known, active and eligible, or the run is refused before any quota is used (status INVALID_FILTER, exit code 1). Unset, every eligible instrument is searched.
6. **The safe defaults stay.** The monthly quota stays at 1,000 until Azeez approves a new one from a live billed-credit benchmark. Ingestion stays off until that approval is recorded.

### Part 2: the multi-source client report (not yet built)

7. A classified source joins an existing event only if its sorted set of affected instruments, its event type and its direction all match within a rolling 72 hours; ambiguous or conflicting classifications stay separate. All linked sources are kept; at most five distinct-domain sources go to the model, chosen by publication time and canonical URL. The first report is not delayed waiting for more sources.
8. One `event_report` per `public_event`, with a `public_event_source` join table. Existing briefings are backfilled and the old `briefing` table is kept for rollback. A report is generated once per event, only after deterministic scoring gives at least one alert of 60 or more.
9. The model returns the closed schema `alert-client-report-v2`: headline; an executive summary of two or three cited sentences; up to four key facts with source references; why it matters, cited or marked as a potential implication; a nullable market context, always omitted until an accepted, timestamped market-data feed exists; two to four things to monitor; uncertainty; source references.
10. Every factual claim must cite the supplied evidence, and every number in a key fact must appear in a cited title or excerpt after normalisation. An invalid citation, an unsupported figure, advice language or malformed output makes the model abstain.
11. Application code, not the model, adds the portfolio line "Your portfolio has exposure to {issuer}, which may be affected by this event.", the fixed informational-purpose disclaimer and the relationship manager's contact details.

### Part 3: delivery (not yet built)

12. Reports are delivered as individually addressed emails through the existing engagement service and SES, only after the assigned agent selects the recipients and approves the shared report. A newly linked corroborating source creates a new report version for the agent to review; it is never sent on its own.

## Consequences

- Coverage is complete every run, at a cost of two searches per eligible instrument: twenty per run for ten instruments, 120 a day at six runs a day, about 3,600 a month. That exceeds the safe 1,000-search default, so the quota must be raised, with Azeez's approval, before many instruments are eligible. Until then a run that does not fit searches nothing.
- The budget estimate counts every search at a fixed cost and does not read Tavily's real billing.
- Part 2 changes the pipeline's core tables and the event cards and is a larger change than part 1.

## Security and Privacy Impact

Only public terms go to Tavily and only public excerpts to the model, as before. Reports never contain client, account or holding data from the model; the portfolio line is added by application code. Delivery needs a recipient email address, which comes from the CRM at send time and is not stored in the alerts database.

## Cost Impact

Tavily searches rise to two per eligible instrument per run. The budget guard (USD 25 a month) and the quota stop a run they cannot cover. Part 2 adds one model call per event instead of one per alert, which lowers model cost.

## Operational Impact

New setting `ALERT_SEARCH_ONLY_INSTRUMENT_ID` for lower environments and `ALERT_ESTIMATED_SEARCH_COST_USD`. `ALERT_MAX_QUERIES_PER_RUN` and `ALERT_MAX_SEARCHES_PER_RUN` now default to 1,000 as sanity ceilings. No Terraform change for part 1.

## Validation

Part 1: unit tests cover the exact query groups, two searches per instrument with no cap, no rotation, the budget and quota reservations, and the single-instrument setting, including refusal of an unknown, inactive or ineligible instrument before any reservation. Not verified: a live billed-credit benchmark, and parts 2 and 3.

## References

- [ADR-0049](ADR-0049-alert-source-to-alert-pipeline.md), [ADR-0050](ADR-0050-event-search-provider-after-scp-block.md), [ADR-0051](ADR-0051-single-issuer-search-and-relevance-gate.md), [ADR-0066](ADR-0066-crm-linked-current-alert-holdings.md)
- [Feature plan](../feature-plans/event-driven-alerts-full-coverage-reports_plan.md)
