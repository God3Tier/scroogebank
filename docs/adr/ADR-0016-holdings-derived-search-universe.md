# ADR-0016: Holdings-derived public search universe

- Status: Proposed
- Decision date/time: 2026-10-28 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/search-universe-thresholds`
- Supersedes: None

> **2026-10-29:** Decision 6's 32-instrument grouped-query shape is superseded
> by [ADR-0051](ADR-0051-single-issuer-search-and-relevance-gate.md). Holdings
> eligibility and per-instrument coverage decisions remain in force.

## Context

The initial ingestion implementation searched every active instrument in a
configured catalogue. The feature owner wants discovery to follow what clients
actually hold, without sending client or portfolio data to Tavily. Holdings use a
stable instrument identifier, while the service-owned public instrument catalogue
maps that identifier to ticker, issuer, sector, currency, and active state.

Selecting outbound terms from holdings creates an inference risk even when the
terms themselves are public: a very rare selected instrument could reveal the
shape of the bank's portfolio. The selection therefore needs aggregation,
minimum-population controls, bounded output, and a fail-closed unknown-ID path.

## Decision

The Singapore worker derives the search universe locally before calling Tavily:

1. Read the approved holdings snapshot through `SearchUniverseProvider`.
2. Ignore zero-quantity and zero-value positions.
3. Count distinct clients with positive positions per stable instrument ID.
4. Select an instrument when either it is held by at least two clients and at
   least 20% of clients with a positive snapshot position, or it represents at
   least 50% of one account's positive SGD portfolio value. Client and account
   scopes are never combined. The controls are configurable and inclusive.
   Missing, expired, invalid, or incompatible valuations fail closed only for
   concentration; the commonality rule remains independent of valuation state.
5. Join eligible IDs to the Singapore-side instrument catalogue and discard
   unknown or inactive identifiers.
6. Sort eligible active instruments deterministically and select at most 32 for
   the current four-hour UTC schedule slot. Build at most four grouped searches
   from public ticker, issuer, sector, alias, and event terms.
7. Persist each instrument's `last_successful_to`. New instruments use the
   four-hour initial lookback; returning instruments resume from their previous
   successful time minus overlap. Only a successful scheduled run advances
   coverage. Failures, blocked runs and replays do not.

Tavily receives only the resulting public terms, approved domains, date window,
and result limit. It never receives client counts, client share, client/account or
agent identifiers, holdings, quantities, values, exposure, or the selection
rationale. If no instrument passes the controls, the run performs no search.

Instrument-to-category mapping is deterministic reference data, not model output.
For the MVP, `instrument.sector` is the supported category. A future industry or
classification taxonomy must record its provider, version, update cadence, and
licensing before it replaces or extends this field.

The synthetic MVP seeds a small multi-market catalogue and 20 opaque portfolios
through Flyway. Its deterministic fixture spans SGX, NASDAQ, NYSE, LSE, and HKEX
without real client data. A real holdings provider and governed instrument master
remain separate future integration decisions.

## Consequences

Searches adapt automatically as the approved holdings snapshot changes and do not
require a manually maintained sector search list. The population controls reduce,
but do not eliminate, selection-inference risk. Rare but important holdings can be
missed, so thresholds require evaluation and human approval before production use.
Sector terms broaden discovery; instrument matching and per-client materiality
scoring still happen after results return to Singapore.

## Validation

- Unit tests cover the exact 20% and 50% boundaries, per-account isolation,
  invalid valuation failure, zero-value exclusion, rotation and recovery,
  inactive/unknown instrument removal, and the empty-universe no-search path.
- Payload tests continue to reject client, account, holding, exposure, and agent
  fields.
- A six-call local Tavily benchmark on 2026-10-29 verified the bounded transport
  but failed the usefulness gate (one of 12 eligible instruments represented and
  no actionable taxonomy event in the expanded pass). Ingestion remains off.
- Hayyun must review the holdings/domain behavior before this ADR is accepted.
