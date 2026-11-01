# ADR-0005: Alert holdings and valuation boundary

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-09-27 01:54:06 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/contracts`
- Supersedes: None
- Superseded by: [ADR-0066](ADR-0066-crm-linked-current-alert-holdings.md) in part (the runtime holdings source: with `alert_holdings_mode = "database"` the API and worker read the portfolios agents record against CRM accounts; the bundled fixture stays the default; valuation snapshots and freshness rules are unchanged)

## Context

The CRM has no securities, positions, prices, or portfolio valuation source. The
alert MVP needs deterministic exposure calculations but cannot introduce a live
market-data dependency or read another service's tables directly.

## Decision

The alert service owns a small synthetic holdings catalogue for the MVP. Each
position uses opaque synthetic client and account references, an instrument ID,
quantity, valuation currency, unit price, FX rate to SGD, valuation timestamp,
and source label. Values use fixed-precision decimals. The service stores the
exact valuation snapshot used by every score so results can be reproduced.
Holdings reads require an assigned-agent subject and return positions only for
that scope; mixed client, account, or agent positions cannot be combined into an
exposure result.

A valuation is `FRESH` for 24 hours, `STALE` from 24 to 72 hours, and `EXPIRED`
after 72 hours. Missing or expired values cannot create an alert. Stale values may
create an alert only with a visible warning and a deterministic score penalty.
No live feed, inferred price, or silent currency default is allowed.

## Consequences

The demo is reproducible and remains synthetic, but it does not demonstrate a
production portfolio integration. A future real holdings provider must implement
the frozen read contract and requires its own security, lineage, and licensing
decision.

## Validation

Fixture tests cover fresh, stale, expired, missing, multi-currency, and rounding
cases. Replaying the same event and snapshot must produce the same exposure and
score.
