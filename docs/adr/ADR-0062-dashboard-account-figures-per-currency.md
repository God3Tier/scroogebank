# ADR-0062: Dashboard account figures per currency

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-10-24 20:00 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen; Joseph Yau (Feature 2 primary developer, whose account-service this changes; agreement pending)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/business-dashboard/per-currency-deposits`
- Related issue/PR: TBD
- Supersedes: [ADR-0038](ADR-0038-dashboard-metric-definitions.md) in part: the account-service refusal (HTTP 409) and the `mixed_currencies` panel in its currency rules, the single-currency premise that follows them, and "Booked initial deposits" as one sum
- Superseded by: N/A

## Context

[ADR-0038](ADR-0038-dashboard-metric-definitions.md) rules that no sum crosses currencies. account-service enforces it by refusing the whole `GET /aggregates/accounts/stats` call (HTTP 409) when the accounts opened in the window use more than one currency, and the dashboard shows those panels as unavailable with `mixed_currencies`.

The refusal costs far more than the sum. One USD account opened in the window takes away:

- every account count and breakdown (by type, status, currency and branch), none of which is a sum;
- the admin's whole per-agent table, including clients onboarded and accounts opened, which do not involve currency at all.

ADR-0038 accepted this because the deployment was taken to be single-currency by specification. That premise no longer holds in this system:

- account-service accepts any three-letter currency.
- The producer writes each account's transactions in that account's currency, and a USD account was run on the deployed stack ([ADR-0045](ADR-0045-producer-account-list-synced-from-crm.md), revised 2026-10-24).
- The import checks every transaction against its account's currency ([ADR-0061](ADR-0061-imported-transactions-in-their-accounts-currency.md)).
- The specification's Appendix 2 describes the field as "Currency in which the account operates (SGD for Singapore Dollar)": an example, not a limit. The Project Requirements Q&A says nothing on currency.

The transaction figures already handle this well: the receiver leaves the value out when the window's transactions span currencies, lists the currencies, and still returns every count.

## Decision Drivers

- No sum crosses currencies (ADR-0038, unchanged).
- A figure that does not involve currency is never lost because of currency.
- An amount is always shown with its currency.
- Staff ranking stays on one explicit figure, never a derived score (ADR-0038).

## Options Considered

### Option A: keep the refusal

- Pros: no change.
- Cons: one account in a second currency empties the account panels and the per-agent table.

### Option B: return the counts and leave the deposits out when mixed

- Pros: the smallest change; matches the transaction figures.
- Cons: the deposit figure disappears whenever a second currency appears, though each currency's sum is well defined.

### Option C: return the counts and sum the deposits per currency (chosen)

- Pros: every figure the data supports is shown; nothing is added across currencies.
- Cons: the deposits become a map, and ranking agents by deposits needs a currency.

### Option D: convert to SGD

- Pros: one number.
- Cons: the system has no exchange-rate source; a converted figure would be the plausible-but-wrong number ADR-0038 exists to prevent.

### Option E: accept only SGD when an account is opened

- Pros: matches a strict reading of Appendix 2.
- Cons: contradicts ADR-0045 and ADR-0061, which already handle other currencies; leaves existing non-SGD accounts; changes account creation, which this decision does not need.

## Decision

**Option C.**

1. **account-service never refuses over currency.** `GET /aggregates/accounts/stats` always returns the counts and breakdowns.
2. **Booked initial deposits are summed per currency**: `bookedInitialDeposits` is `{currency: amount}`, for the window and for each agent. `currencies` lists the window's currencies, sorted; it is empty when no account was opened.
3. **Each day's `value` in `dailyOpened` is null when the window's accounts span more than one currency**, as the receiver does for transactions. Each day's count is always given.
4. **The per-agent table ranks deposits in one named currency.** `GET /api/dashboard/agents` takes `currency` (three upper-case letters, default `SGD`). An agent with no deposits in it counts as zero. The panel names the currency it ranked by (`sortCurrency`) and the window's currencies.
5. **The `mixed_currencies` panel reason is removed**; nothing produces it.
6. **Transaction value is shown with its currency** when the window's transactions share one, and left out with the currencies listed otherwise (the receiver is unchanged).
7. **Account creation is not limited to SGD** (Option E rejected).

ADR-0038's definitions otherwise stand; its table row now reads "Sum of `initial_deposit` for accounts opened in the window, per currency".

## Rationale

The rule ADR-0038 protects is about sums. Refusing the call protected it by giving up figures that are not sums. Summing per currency keeps the rule exactly and shows each amount with the currency it is in. Ranking by one named currency keeps the per-agent ordering to one explicit figure.

## Consequences

### Positive

- The account panels and the per-agent table show whatever currencies the accounts use.
- Every amount on the dashboard carries its currency.

### Negative / Trade-offs

- The account stats response changes shape (`currencies`, deposit maps, nullable daily values). account-service and dashboard-service must be deployed together; a new dashboard reading an old account-service shows `upstream_error` for those panels, no worse than before.
- Ranking by deposits compares agents in one currency at a time.
- [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md)'s list of expected unavailability reasons still names mixed currencies; that reason no longer occurs.
- Other features' single-currency assumptions are not changed here: client-health's plan and contract still expect SGD until their own currency policy ([client-health-monitor_plan.md](../feature-plans/client-health-monitor_plan.md), [client-health-monitor-contracts.md](../api/client-health-monitor-contracts.md)). Their owners may cite this decision.

## Security & Privacy Impact

None new. The same scopes and roles apply; the per-agent table stays admin-only and audited (ADR-0038). A currency code is not personal data, and an invalid `currency` is refused without being echoed.

## Cost Impact

None. The queries group by one more column.

## Operational Impact

Deploy account-service and dashboard-service together. The dashboard cache keys the per-agent table by currency as well as sort.

## Validation

- account-service: repository tests with two currencies in the window return the counts, a deposit map with one entry per currency, null daily values and per-agent maps; a single-currency window returns a one-entry map and daily values.
- dashboard-service: the per-agent table ranks by a chosen currency with missing currencies as zero; the adapter parses the new shape; `currency` defaults to SGD and a malformed one is refused; two currencies are cached apart.
- Frontend: the deposits tile and table show each currency; the currency picker appears only for the deposits sort with more than one currency.

## References

- [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md), [ADR-0038](ADR-0038-dashboard-metric-definitions.md), [ADR-0045](ADR-0045-producer-account-list-synced-from-crm.md), [ADR-0061](ADR-0061-imported-transactions-in-their-accounts-currency.md)
- CS301 project specification, Appendix 2; Project Requirements Q&A (no currency entries)
