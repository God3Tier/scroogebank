# ADR-0032: X-factor 1 instrument master, deterministic matching (no vector similarity) and topic-selection thresholds

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

The x-factor 1 worker ([ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md)) must know each holding's sector and industry, decide which instruments to search for, and match public news back to the holdings it affects. These steps decide which agent sees which alert, so they must be reproducible, explainable and auditable. An outbound query naming a rarely held instrument could reveal that some client holds it.

The feature owners' Proposed ADRs on `feat/event-driven-alerts` cover related ground: branch ADR-0015 (deterministic materiality scoring) and ADR-0016 (holdings-derived public search universe), both owned by Azeez.

## Decision Drivers

- Reproducibility and explainability of every alert
- Privacy of rare holdings
- No vector infrastructure without a need
- Search cost

## Options Considered

### Categorisation

- **Deterministic instrument master (chosen):** sector and industry stored once per instrument, from an approved provider.
- **LLM classification:** not reproducible, and can invent sectors.
- **Vector similarity:** not deterministic; needs a vector store; harder to explain and audit.

### Matching news to holdings

- **Deterministic: exact ticker or instrument, sector and named-company match (chosen).**
- **Vector relevance scoring:** rejected for the same reasons.

### Topic selection

- **Search only instruments held by at least 2 clients and at least 5% of clients (chosen):** both thresholds configurable.
- **Search every holding:** would expose rare holdings through outbound queries and multiply search cost.

## Decision

1. **Instrument master** in alerts-db. Categories are stored against the instrument, not against each holding.
   - Production fields:
     - stable instrument ID, ISIN and exchange ticker, and issuer;
     - asset class, sector, industry and sub-industry;
     - currency, and country or market;
     - active status, classification provider and version, and last-updated time.
   - Data comes from an approved security-master or market-data provider ([open item 15](../architecture/open-items.md#15-instrument-master-source)).
   - Updates are versioned and audited.
   - An instrument missing from the master is **UNCLASSIFIED** and excluded from automated sector searches until reviewed. **An LLM is never asked to guess.**
   - The MVP uses a small synthetic instrument table.
2. **Account sector exposures** are recalculated from holdings through the master. An account has no single permanent sector.
3. **Topic selection:** an instrument is searched only if it is held by **at least 2 clients and at least 5% of clients**, so a rare holding can never be revealed through an outbound query.
4. **Matching** is deterministic: exact ticker or instrument, sector, and named company. Relevance weighs:
   - direct versus sector-level impact;
   - exposure amount and share;
   - event confidence;
   - data freshness;
   - alert thresholds.
5. **No vector similarity anywhere** in this feature. No vector store is deployed.

## Rationale

The same inputs always produce the same alerts, and every alert can show exactly why it matched. The thresholds protect client privacy and also cap search volume.

## Consequences

### Positive

- Reproducible, explainable, auditable alerts.
- No vector infrastructure or embedding cost.

### Negative / Trade-offs

- News phrased without a ticker, sector or company name may be missed.
- Depends on the quality and coverage of the instrument master.
- Instruments held by very few clients are never searched, even when news is relevant to them.

## Security & Privacy Impact

The thresholds stop outbound queries from revealing rare holdings. Holding counts and shares themselves never leave Singapore.

## Cost Impact

No vector store. The thresholds keep search volume within the caps in ADR-0031.

## Operational Impact

Instrument master updates and reclassifications are reviewed and audited. UNCLASSIFIED instruments need a review queue.

## Validation

- Repeating a run with the same inputs gives identical matches.
- An instrument held by one client is never in an outbound query.
- An UNCLASSIFIED instrument is excluded from sector searches.

## References

- [X-factor 1: event-driven market-news alerts](../architecture/x-factors.md#x-factor-1-event-driven-market-news-alerts)
- [Open item 15](../architecture/open-items.md#15-instrument-master-source)
- `feat/event-driven-alerts`: ADR-0015 and ADR-0016 (Proposed, owner Azeez)
