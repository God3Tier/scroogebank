# ADR-0015: Deterministic alert materiality scoring

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-09-27 02:44:47 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/scoring`
- Supersedes: None

## Context

The MVP needs an explainable alert threshold that can be replayed exactly from
stored inputs. The feature plan names severity, exposure, retrieval relevance,
and classifier confidence, but does not assign weights, rounding, a threshold,
or stale-valuation behavior. These values are prototype controls and must not be
presented as investment advice or UBS policy.

## Decision

`materiality-v1` accepts four normalized decimal inputs in the inclusive range
zero to one. It calculates a score from zero to 100 using these fixed weights:

| Factor | Weight |
|---|---:|
| Event severity | 30 |
| Portfolio exposure | 35 |
| Retrieval relevance | 15 |
| Classifier confidence | 20 |

Each contribution and the final score are rounded half-up to two decimal places.
The alert threshold is 60.00. A stale valuation subtracts 10.00 and produces a
visible warning. Missing or expired valuations do not produce a score or alert.

Exposure is affected SGD holding value divided by the positive total SGD value
for exactly one synthetic client/account/assigned-agent scope. Position value
must equal quantity × unit price × FX-to-SGD, rounded half-up to four decimal
places. Portfolio valuation timestamps may differ by at most 24 hours. Mixed
scope, invalid value, missing total, and incompatible timestamps are explicit
not-evaluable outcomes. The MVP does not support negative/short positions.

## Consequences

The same inputs reproduce the same score and explanation factors without a
model or network call. The simple weights make review and tuning easy, but they
are not empirically calibrated and may create false positives or negatives.
Changing a weight, threshold, rounding rule, penalty, or compatibility window
requires a new rule version and evaluation evidence.

## Validation

- Unit tests cover replay equality, exact rounding, threshold behavior, stale
  penalties, and refusal of missing or expired valuations.
- Holdings/exposure tests cover agent isolation, missing instruments,
  multi-currency conversion, invalid derived values, mixed client scopes,
  incompatible timestamps, and positive portfolio totals.
- Hayyun must review the CRM/domain behavior before this ADR is accepted or the
  feature is proposed for merge into `dev`.
