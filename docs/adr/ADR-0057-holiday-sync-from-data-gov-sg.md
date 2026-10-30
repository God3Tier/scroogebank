# ADR-0057: Sync Singapore public holidays from MOM's data.gov.sg dataset

- Status: Accepted
- Decision date/time: 2026-10-21 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/agent-engagement/holiday-sync`
- Related issue/PR: TBD
- Supersedes: [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md) decision 8 in part (new years no longer need a migration)
- Superseded by: N/A

## Context

[ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md) decision 8 keeps public holidays in a `holidays` table seeded by Flyway migrations. Singapore 2026 and 2027 are seeded from the Ministry of Manpower (MOM); each new year needs someone to write and check another migration.

Singapore's official dates exist only once MOM publishes them, about 18 months ahead: the 2027 list was published on 3 July 2026. Several holidays follow lunar or religious calendars, so dates for later years are projections that can be a day out.

MOM publishes the lists on data.gov.sg under the Open Data Licence: one dataset per year, and a consolidated dataset, `d_8ef23381f9417e4d4254ee8b4dcdb176`, updated each year. On 2026-10-21 it held 104 rows from 2020 to 2027. Its open `datastore_search` API returns `date`, `day` and `holiday`. Observed days are separate rows named "… (Observed)", and some names use a curly apostrophe. Without an API key, requests are rate-limited.

Two smaller problems follow from a fixed list:

- the calendar lets an agent page into months with no holiday data, which looks like a year without holidays;
- holiday-greeting drafts for early January need the next year's list by December.

## Decision Drivers

- New years appear without a code change or migration.
- Only official dates; no projections.
- Holiday names reach the drafting model (ADR-0054 decision 2), so incoming data must be checked.
- Nothing about clients or agents leaves the service.
- The calendar never shows a month the list does not cover.

## Options Considered

- **Sync MOM's consolidated dataset from data.gov.sg (chosen).**
  - Pros: official; free; new years arrive within a day of publication, or at the next session's start.
  - Cons: an outside dependency, and rate limits without a key. A failed fetch only delays new years, because existing rows stay.
- **A third-party holiday API (for example Calendarific or Nager.Date).**
  - Pros: many countries; dates for later years.
  - Cons: dates before MOM's announcement are projections; Calendarific needs an API key. A greeting on the wrong day is worse than none.
- **A migration each year (ADR-0054 as accepted).**
  - Pros: no outside calls; every row reviewed.
  - Cons: someone must remember each year, and the calendar limit would be hard-coded.

## Decision

1. **Source.** MOM's consolidated "Singapore Public Holidays" dataset on data.gov.sg, read page by page (100 rows) through `datastore_search`. Only the dataset ID is sent. More than 2,000 rows, a response without `success: true`, or an HTTP error is a failure.
2. **Checking each row.**
   - The date must be ISO and between 2000 and 2100.
   - The name has curly apostrophes made straight and spaces collapsed; "(Observed)" becomes `observed = true` with the base name, matching the seeded rows.
   - The name must be 1 to 100 letters, digits, spaces and `'().,&-`; anything else is dropped and counted in the log.
3. **Writing.** Rows are only added, with `INSERT … ON CONFLICT (country_code, holiday_date, name) DO NOTHING`, `source` set to the dataset's page and `dataset_version` to `data.gov.sg-<date>`. Rows are never changed or deleted, because drafts refer to them. A stored row in a year the source covers that the source does not list is logged as `holiday_sync_mismatch`, for a person to correct with a migration.
4. **When.** One minute after each start, then every 24 hours, behind `ENGAGEMENT_HOLIDAY_SYNC_ENABLED` (off by default, on in AWS). The stack is stopped between sessions, so the start-up run usually picks up a new year. Two tasks may sync at once; the unique key makes that harmless.
5. **Coverage.** `GET /api/engagement/holidays/coverage?country=SG` returns `coveredThrough`, 31 December of the latest year with holidays, or null. The calendar does not offer months after it; if coverage cannot be read, it sets no limit.
6. **Alarm.** From October, a list without next year logs `holiday_coverage_short`. That and `holiday_sync_mismatch` raise the `<prefix>-engagement-holiday-data` alarm to the CRM's alarm topic.
7. **Unchanged.** The migrations stay as the baseline for local runs and tests; the table, lookups and country mapping are as ADR-0054 decision 8 says.

## Rationale

MOM's own dataset is the only source with exact dates, and it is published in a machine-readable form. Syncing it removes the yearly migration without adding projections. Adding rows only, never changing them, keeps drafts' references stable and makes a bad response unable to remove data. Checking names strictly keeps the model's input to plain holiday names. Basing the calendar limit on what the table holds keeps it right every year.

## Consequences

### Positive

- A newly published year appears without a release.
- The calendar ends where the holiday list ends, and moves with it.
- A missing year or a changed date raises an alarm.

### Negative / Trade-offs

- A dependency on data.gov.sg; a failed fetch is retried the next day or at the next start.
- A date MOM moves after publication is not corrected automatically; the alarm asks for a migration.
- Without an API key, the sync shares data.gov.sg's anonymous rate limit, which one request a day stays far below.

## Security & Privacy Impact

- Outbound HTTPS to data.gov.sg through the NAT gateway; only the dataset ID is sent.
- Incoming names are checked before they can reach the model (ADR-0054 decision 2).
- No new secrets or IAM permissions.

## Cost Impact

None. data.gov.sg is free; one log metric filter and one alarm are within the free tier.

## Operational Impact

- `ENGAGEMENT_HOLIDAY_SYNC_ENABLED=true` in the agent-engagement stack.
- New alarm `<prefix>-engagement-holiday-data`; the runbook's troubleshooting table says what to do.

## Validation

- `OfficialHolidayTest`: observed rows and curly apostrophes match the seeded form; bad dates and names are dropped.
- `DataGovSgHolidaySourceTest`: every page is read; HTTP errors, `success: false`, too many rows and non-JSON fail.
- `HolidaySyncTest`: a new year is added once, seeded rows are not duplicated, coverage moves to the new year, a failed fetch changes nothing, and missing next-year data is reported from October.
- `AgentCalendar.test.jsx`: Next month stops at the covered month; unknown coverage sets no limit.
- terraform test: the sync is on in AWS, and the alarm's filter matches both log events.

## References

- data.gov.sg: [Singapore Public Holidays (consolidated)](https://data.gov.sg/datasets/d_8ef23381f9417e4d4254ee8b4dcdb176/view), [Public Holidays for 2027](https://data.gov.sg/datasets/d_0ba69fc6d56717ff0bf8083c6af3bb84/view)
- [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md), [ADR-0027](ADR-0027-observability-baseline.md)
- [Agent engagement API](../api/engagement-api.yaml)

## Revision history

- 2026-10-21: proposed and accepted; the decision owner chose the data.gov.sg sync with a calendar limit that follows it.
