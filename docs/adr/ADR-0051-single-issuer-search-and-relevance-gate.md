# ADR-0051: Single-issuer search and deterministic retrieval relevance gate

- Status: Proposed
- Decision date/time: 2026-10-29 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/retrieval-usability`
- Supersedes: ADR-0016 decision 6's 32-instrument grouped-query design
- Amends: ADR-0050 decision 2's Tavily request shape

## Context

The global-portfolio benchmark proved the Tavily transport but not retrieval
usefulness. Up to eight issuers and their sectors competed for ten result slots,
so the expanded pass represented only one of 12 eligible instruments and no
actionable event. Eighteen follow-up basic-search calls compared grouped,
single-issuer, `finance`, exact-match, broad-web and restricted-source variants.
`finance` and exact-match were brittle; broad-web search increased noise. The
promising shape was one issuer per `news` query with issuer-specific official
domains and a small candidate commercial-news allowlist.

ADR-0011 still requires terms and storage/display rights to be recorded before
a commercial publisher is enabled outside a bounded local benchmark.

## Decision

1. Build one Tavily query per selected issuer. It contains the public legal
   issuer name, approved aliases, ticker and a compact set of high-signal event
   discovery terms. The complete controlled event vocabulary remains a local
   filter rather than a search-engine term dump. Sector terms and multi-issuer
   Boolean groups are removed.
2. Store search aliases and official domains as governed instrument-master
   reference fields. Each request restricts results to that issuer's official
   domains plus any explicitly configured, reviewed shared news domains.
3. Keep `topic=news`, `search_depth=basic`, at most ten results/query and four
   queries/run. Reuters, CNBC and FT are benchmark candidates, but deployment
   configuration stays empty until their ADR-0011 terms review is recorded.
4. Before persistence, the normalized result title must contain a controlled
   actionable-event phrase and normally the issuer's legal name or approved
   alias. On governed issuer/exchange domains only, the issuer name may occur in
   the bounded excerpt when the title still names the event.
   Ticker-only, sector-only, product-only and cross-issuer results are rejected.
   Explicit preview phrases such as `set to report`, `ahead of earnings` and
   `what to expect` are also rejected because they describe anticipation rather
   than a completed public event.
5. Select at most four instruments per four-hour slot. Per-instrument coverage
   resumes from its last successful timestamp minus overlap, so rotation delays
   discovery but does not discard unsearched time. Twelve eligible instruments
   complete a rotation in about 12 hours.
6. Production enablement requires a human-reviewed historical benchmark with
   Recall@10 at least 80%, actionable precision at least 60%, zero persisted
   cross-issuer results, one credit per basic query and every call within the
   existing 30-second timeout.

## Consequences

The four-call and 720-credit monthly planning ceiling stays unchanged. Result
slots no longer compete across issuers, and irrelevant results are removed before
storage or model use. The trade-off is up to roughly 12 hours before every member
of the current 12-instrument universe is searched. A larger universe rotates
more slowly and needs an explicit cost/freshness decision rather than silent
query grouping.

Commercial publishers are not production-enabled by this ADR. If the revised
benchmark fails, ingestion remains off and a later ADR must choose direct
exchange/regulatory feeds or another provider.

## Validation

### Requester direction, 2026-10-30

The requester explicitly directed: "just use it but attach the sorce in the
summaary as well" after discussion of the unverified publisher rights.
For this task, that direction supersedes the empty commercial-domain
configuration in decisions 2–3: Reuters, CNBC and FT are configured in the
application and worker defaults, and the event summary displays a clickable
source-domain citation. This records the requester's configuration decision;
it does not assert a publisher licence, completed terms review, or approval
by a named ADR reviewer. Azeez remains the accountable human owner.

Scheduled ingestion and AI remain disabled by default. Retrieval quality,
AWS/model verification, bounded excerpts and retention requirements still apply.

### Existing evidence

- Unit tests cover one-query-per-issuer construction and issuer/event filtering.
- PostgreSQL migration tests verify aliases and official domains.
- Rotation tests cover 12 instruments in three slots and 100 in 25 slots.
- The worker benchmark and source register provide the enablement evidence.
- A 12-issuer live smoke test exposed and removed excerpt-driven false positives
  and earnings previews. The final four-issuer confirmation returned seven of
  seven manually actionable, issuer-correct results. Recall@10 remains unproven,
  so this is not production-enablement evidence.
