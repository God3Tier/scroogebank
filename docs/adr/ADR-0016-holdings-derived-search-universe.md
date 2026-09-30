# ADR-0016: Holdings-derived public search universe

- Status: Proposed
- Decision date/time: 2026-09-30 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/integration`
- Supersedes: None

## Context

The initial ingestion implementation searched every active instrument in a
configured catalogue. The feature owner wants discovery to follow what clients
actually hold, without sending client or portfolio data to Tokyo. Holdings use a
stable instrument identifier, while the service-owned public instrument catalogue
maps that identifier to ticker, issuer, sector, currency, and active state.

Selecting outbound terms from holdings creates an inference risk even when the
terms themselves are public: a very rare selected instrument could reveal the
shape of the bank's portfolio. The selection therefore needs aggregation,
minimum-population controls, bounded output, and a fail-closed unknown-ID path.

## Decision

The Singapore worker derives the search universe locally before calling Tokyo:

1. Read the approved holdings snapshot through `SearchUniverseProvider`.
2. Ignore zero-quantity and zero-value positions.
3. Count distinct active clients per stable instrument ID.
4. Select an instrument only when it is held by at least two clients and at least
   5% of clients represented in the snapshot. Both controls are configurable,
   default to `2` and `0.05`, and may only be changed through an audited operator
   process once that contract exists.
5. Join eligible IDs to the Singapore-side instrument catalogue and discard
   unknown or inactive identifiers.
6. Build at most four grouped searches from public ticker, issuer, sector, alias,
   and event terms.

Tokyo receives only the resulting public terms, approved domains, date window,
and result limit. It never receives client counts, client share, client/account or
agent identifiers, holdings, quantities, values, exposure, or the selection
rationale. If no instrument passes the controls, the run performs no search.

Instrument-to-category mapping is deterministic reference data, not model output.
For the MVP, `instrument.sector` is the supported category. A future industry or
classification taxonomy must record its provider, version, update cadence, and
licensing before it replaces or extends this field.

The synthetic MVP seeds only its synthetic instrument reference row through
Flyway. A real holdings provider and instrument master remain separate future
integration decisions.

## Consequences

Searches adapt automatically as the approved holdings snapshot changes and do not
require a manually maintained sector search list. The population controls reduce,
but do not eliminate, selection-inference risk. Rare but important holdings can be
missed, so thresholds require evaluation and human approval before production use.
Sector terms broaden discovery; instrument matching and per-client materiality
scoring still happen after results return to Singapore.

## Validation

- Unit tests cover client-count/share selection, zero-value exclusion, public
  sector query terms, inactive/unknown instrument removal, and the empty-universe
  no-search path.
- Payload tests continue to reject client, account, holding, exposure, and agent
  fields.
- The AgentCore spike must inspect actual outbound requests before enablement.
- Hayyun must review the holdings/domain behavior before this ADR is accepted.
