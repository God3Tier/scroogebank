# Tavily global-portfolio benchmark — 2026-10-29

## Outcome

The actual worker and Tavily adapter completed all three local passes, but the
benchmark **failed the usefulness gate**. Keep ingestion disabled. The expanded
official-domain pass represented only one of 12 eligible instruments, and its
single accepted result was not an earnings, regulatory, merger, credit, or
leadership event.

## Synthetic dataset and controls

- 20 opaque clients, four synthetic agents, 16 real public instruments across
  SGX, NASDAQ, NYSE, LSE, and HKEX, plus the legacy test-only synthetic row.
- 95 positions total; each client holds four to six real instruments and totals
  exactly SGD 100,000 using fixed synthetic FX.
- 12 instruments pass the inclusive 20%/two-client commonality or 50%
  per-account concentration rule.
- Fresh PostgreSQL 16 databases with Flyway V1–V8; AI disabled; no AWS/Bedrock
  call. The replay lookback was changed only at runtime from four hours to 30
  days for the first two passes.
- Two grouped queries per pass, one attempt each: six Tavily basic calls total.
  The adapter does not request Tavily's usage object, so provider-dashboard
  confirmation remains the billing source of truth.

## Results

| Pass | Window/domains | Attempts | Accepted | Duplicate | Wall time | Useful result |
|---|---|---:|---:|---:|---:|---|
| Baseline | 30 days; `mas.gov.sg`, `sgx.com` | 2 | 1 | 0 | 32.45 s | DBS leadership appointment; actionable |
| Expanded | 30 days; reviewed exchange/regulator/issuer official domains | 2 | 1 | 1 | 32.73 s | Apple product announcement; issuer-relevant but outside taxonomy |
| Recency | 4 hours; expanded domains | 2 | 0 | 0 | 37.02 s | None |

The baseline accepted SGX's “Appointment of Non-Executive and Independent
Director” for DBS/D05, published 2026-10-01. The expanded pass accepted Apple's
“iPhone Duo arrives at Apple Store locations worldwide”, published 2026-10-23.
Local exact-window/domain validation and canonical-URL deduplication behaved as
designed.

## Gate assessment

- Worker/provider transport: **pass**.
- Approved-domain and exact-date enforcement: **pass**.
- Persistence and deduplication: **pass**.
- Eligible-instrument coverage: **fail** — 1/12 in the expanded pass.
- Actionable taxonomy precision: **fail** — 0/1 expanded accepted results.
- Improvement over SGX/MAS baseline: **fail** — no additional useful result.

## Decision

Do not enable scheduled ingestion. The next design iteration should benchmark
smaller or per-instrument queries, and only consider another provider if the
bounded Tavily design still cannot meet coverage and actionable-precision gates.
Repeat the same synthetic universe and record account-dashboard credits before
changing the enablement decision.

## Follow-up query-design investigation

Eighteen additional one-credit basic calls compared four representative issuers
(DBS, Apple, HSBC and Tencent):

- Tavily's `finance` topic returned no result for DBS, HSBC or Tencent and an
  unrelated SGX report for Apple.
- Exact-match with official-only domains suppressed the valid DBS appointment
  and still returned cross-issuer Apple/Tencent results.
- Broad-web `prefer` found strong Reuters/CNBC HSBC coverage but produced heavy
  noise for the other issuers.
- Single-issuer `news` queries restricted to issuer-official domains plus
  Reuters/CNBC/FT produced useful DBS, Apple and HSBC candidates, while Tencent
  still lacked an issuer-relevant result.

This investigation selected ADR-0051's design but did not run the complete
12-issuer labelled production gate. Ingestion remains disabled.

## ADR-0051 implementation benchmark

The implemented single-issuer design was exercised against 12 eligible issuers
from the same 20-client fixture over a 30-day lookback. Reuters, CNBC and FT were
enabled only as local benchmark candidates; no AWS or model call was made.

The first 12-call pass used the full event vocabulary in every query and matched
issuer/event phrases across title plus excerpt. All calls returned results, but
37 passed the local gate and obvious unrelated articles were admitted for DBS,
Apple, JPMorgan and Visa. That design was rejected.

The second 12-call pass used compact high-signal discovery terms and required
both issuer and event phrases in the title. It admitted 18 results for Microsoft,
Alphabet, JPMorgan and HSBC and none for the other eight issuers. Manual review
found that most remaining noise was earnings previews rather than completed
events. The implementation therefore rejects explicit preview language such as
`set to report`, `ahead of earnings`, `earnings playbook`, `what to expect` and
`what to watch`.

A final four-call confirmation over the four issuers with accepted results
produced seven results: two Microsoft results/earnings articles, one Alphabet
earnings miss, two JPMorgan earnings reports and two HSBC profit reports. Manual
review labelled 7/7 issuer-correct and actionable, with no cross-issuer result.
This is a precision smoke test, not a Recall@10 measurement: the fixture does
not yet contain a human-curated independent event set for the 30-day window.

The implementation is usable enough for offline evaluation, but production
ingestion remains disabled because Recall@10 is unproven and commercial-source
terms review is incomplete. These 28 implementation calls are additional to the
six worker calls and 18 query-design calls recorded above. Account-dashboard
confirmation remains required for billing evidence.
