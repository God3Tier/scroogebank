# Full-coverage search and client event reports

Owner: Hayyun, from the plan by Azeez. Reviewers: Azeez, Neo Jia Wen. Status: Part 1 built and tested; parts 2 and 3 not started. Last updated: 2026-11-05.
Parent: [event-driven alerts plan](event-driven-alerts_plan.md). Decision: [ADR-0084](../adr/ADR-0084-full-coverage-search-and-client-event-reports.md) (Proposed).
Branch: `feat/event-driven-alerts`, after merging `task/event-driven-alerts/custom-source-domains` into it.
Estimate in the plan: 10 to 15 developer-days, two to three calendar weeks for three people in parallel.

## Goal and scope

Search every eligible instrument every run, combine corroborating sources into one versioned report per event, and deliver individually addressed reports through the engagement service and SES only after the assigned agent selects recipients and approves. Not in scope: market data, a new quota (needs Azeez's approval from a live billed-credit benchmark), and enabling ingestion.

## Tasks

| ID | Task | Depends on | Status |
|---|---|---|---|
| FC-01 | Merge `custom-source-domains` into `feat/event-driven-alerts` | none | Done |
| FC-02 | Remove the rotation and the four-query cap; two searches per instrument | FC-01 | Done |
| FC-03 | The two query groups as listed in the ADR | FC-02 | Done |
| FC-04 | One atomic reservation per run; quota and USD 25 budget; CONTROL_BLOCKED | FC-02 | Done |
| FC-05 | `ALERT_SEARCH_ONLY_INSTRUMENT_ID`, refused before any quota is used | FC-02 | Done |
| FC-06 | Group sources into one event; `public_event_source`; backfill | FC-02 | Done |
| FC-07 | `event_report` and `alert-client-report-v2`; generate after a score of 60 or more | FC-06 | Done |
| FC-08 | Citation and figure checks; application-added text | FC-07 | Done |
| FC-09 | Agent approves a report and chooses recipients (API and page) | FC-07 | Done |
| FC-10 | Deliver individually addressed emails through the engagement service and SES | FC-09 | Done |
| FC-11 | Documentation, runbook, API contract | all | In progress |

## Open items and risks

- Two searches per eligible instrument will exceed the 1,000-search default as soon as more than a few instruments are eligible. Azeez must approve a new quota before ingestion is enabled.
- Parts 2 and 3 change the pipeline's core tables, the event cards and the engagement service, which other people own.
- The branch `task/event-driven-alerts/search-on-add` holds separate work (shared websites, Search now, fixed schedule, who holds what, a Claude gateway for development); it overlaps this plan in the ingestion files and will need merging with care.
