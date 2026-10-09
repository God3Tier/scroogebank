# ADR-0038: Business dashboard metric definitions and reporting limits

- Status: Accepted
- Decision date/time: 2026-10-09 12:30 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/business-dashboard`
- Related issue/PR: TBD
- Supersedes: N/A (replaces the unmerged branch ADR-0002, "Dashboard metric definitions", whose number is taken on `dev`)
- Superseded by: N/A

## Context

A dashboard is a set of claims about the business. Once a number is on a screen with a confident label, people act on it and nobody re-derives it. So each figure needs a fixed meaning before it is built, and so do the figures this system's data cannot honestly support.

[ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md) has each owning service compute its own figures. The data they hold, as of `dev` after ADR-0035 to ADR-0037:

- **Clients** (client-service): `created_by_agent_id` is the creating agent's Cognito `sub`. `verification_status` is `UNVERIFIED` by default and `verified` (lower case) once verified; no code sets any other value. No lifecycle state.
- **Accounts** (account-service): `owner_agent_id` is copied from the client at creation ([ADR-0035](ADR-0035-account-ownership-across-services.md)). `initial_deposit` is the opening deposit, written once; **no balance exists anywhere**. `currency` defaults to `SGD`, but nothing stops a second value.
- **Transactions** (the SFTP receiver): `account_id` and `client_id` are set on `Matched` rows only ([ADR-0037](ADR-0037-transactions-database-and-account-linking.md)). `currency` is a column, nullable. Statuses are `Completed`, `Pending`, `Failed`; types are `Deposit`, `Withdrawal`.
- **Deleted clients and accounts** keep their rows with `deleted_at` set ([ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md)).
- **Users** (user-service) have a role and an enabled flag, and no team, region, target or assignment history. `idpSub` joins a user to the figures above.

The admin view also ranks named employees by output. That is staff personal data, and how far it goes is a decision.

## Decision Drivers

- Ethical and explainable design (project specification, Appendix 1 principle 10).
- A number that needs a caveat must carry it.
- One place for definitions, referenced rather than re-derived.
- Staff performance data is personal data.

## Options Considered

### Option A — Publish every figure the data can approximate, with caveats

- Pros: the richest dashboard; time-to-verify and similar figures are available at once.
- Cons: caveats are read once and forgotten; an approximate figure about a named employee is the wrong thing to approximate.

### Option B — Publish only figures the data supports exactly (chosen)

- Pros: every number is reproducible from one documented query.
- Cons: a thinner first release.

### Option C — A composite agent score

- Pros: one glanceable number.
- Cons: the weights would be invented; it would read as an assessment of a person while being an arithmetic accident; nothing can check it.

## Decision

**Option B. Option C is rejected outright.**

### Scope and days

- **Admins** see every row. **Agents** see clients whose `created_by_agent_id` is their `sub`, accounts whose `owner_agent_id` is their `sub`, and `Matched` transactions on those accounts. Scope always comes from the verified token, never from a parameter ([ADR-0039](ADR-0039-dashboard-aggregate-endpoints.md)).
- **Deleted clients and accounts are never counted**, for admins too, as every other endpoint treats them (ADR-0036).
- **A window is whole days in Singapore time**, both ends included, at most 365 days. "Today" is the Singapore date.

### Definitions

| Figure | Definition | Caveat shown with it |
|---|---|---|
| Clients | Clients in scope, whenever created | — |
| Clients by verification status | The same, grouped by upper-cased `verification_status` | — |
| Clients onboarded | Clients with `created_at` in the window, per Singapore day and per `created_by_agent_id` | — |
| Verification rate | Verified ÷ onboarded, for clients created in the window; none when an agent onboarded no one | Excludes clients created before the window |
| Stale unverified clients | Clients not verified and created more than N days ago (default 7). The list shows the oldest 50; the count covers all | — |
| Accounts, and by type, status, currency, branch | Accounts in scope, whatever their opening date | — |
| Accounts opened | Accounts with `opening_date` in the window, per day and per `owner_agent_id` | — |
| Booked initial deposits | Sum of `initial_deposit` for accounts opened in the window, **only when they share one currency** | Opening deposit only; not a balance |
| Accounts per client | Accounts ÷ clients, divided by the dashboard from the two totals; none when there are no clients | — |
| Transactions | Transactions with `transaction_date` in the window, by status and by type, per Singapore day | Status is as the source file gave it |
| Transaction value | Sum of `amount` for the same transactions, **only when they share one currency** | Count first, value second |
| Failure rate | `Failed` ÷ transactions, from the status counts | Status is as the source file gave it |
| Pending backlog | Transactions still `Pending` more than 24 hours after their `transaction_date`, whatever the window | — |
| Unmatched transactions (admins only) | Transactions in the window with no `account_id` | Rows the import could not link to an account |
| Team comparison (agents only) | The caller's clients onboarded over the last 7, 30 or 90 days, against the median of the same count across the comparison group (below). No median when the group has fewer than 5 agents | Compared with agents who created a client in the last 12 months |

### Naming and currency rules

- Any figure summing `initial_deposit` is labelled **"Booked initial deposits"**: never "AUM", "deposits held" or "balance". The system knows no balance.
- **No sum crosses currencies.** account-service refuses (HTTP 409) when accounts opened in the window use more than one currency, and the panel says `mixed_currencies`. The receiver leaves the transaction value and each day's value out when the window's transactions span currencies, and lists the currencies instead. A null currency counts as one more currency.
- The deployment is single-currency by specification: Appendix 2 gives the account currency as "SGD for Singapore Dollar", and NFR 12 scopes an instance to one legal entity's data. These rules check that rather than assume it.

### Not built in the first cut

- **Time-to-verify.** Only `updated_at - created_at` exists, which is wrong after any later edit, and would sit next to an employee's name.
- **Any composite agent score or rating.**
- **Client lifecycle figures** (active, dormant, churned): no such state exists.
- **Rejected or pending verifications.** No code sets these statuses.
- **Ingestion freshness.** The receiver keeps no record of its last successful poll; adding one changes Feature 3's poller, so it waits for Joseph's agreement.
- **Clients without an account.** It needs clients and accounts joined across two services.

### The team comparison

An agent's token can read only that agent's figures, and the dashboard holds no credential of its own (ADR-0033). So client-service computes the comparison where the data is and returns only the caller's own count, the median and the group's size (`GET /aggregates/clients/team-comparison`, [ADR-0039](ADR-0039-dashboard-aggregate-endpoints.md)).

- **The comparison group** is every `created_by_agent_id` with at least one client, not deleted, created in the last 12 months. client-service has no list of enabled agents. Asking user-service for one would need a second service credential, which was judged not worth it for one panel.
  - Its known flaws: an agent who has left counts, with zeros, until a year after their last client; a new agent with no clients is not in it; an admin who created a client is in it.
  - Members with no clients in the window count with zero.
- **No median below 5 agents.** With fewer, a median next to the caller's own figure narrows down the others' figures too far. client-service enforces this, and the dashboard checks it again.
- **Only the last 7, 30 or 90 days, ending today.** Medians over many windows of the caller's choosing could be set against each other to narrow down individual figures. Any other dashboard window shows the panel as unavailable with `unsupported_window`.
- **The median is not rounded.** With an odd group it equals one agent's real count, without saying whose.
- Agents only. Admins have the per-agent table.

### Staff performance data

- The per-agent table is **admin-only**: the dashboard checks the role, and client-service and account-service refuse per-agent figures to anyone else. Every view is audited (ADR-0033).
- It shows only work an admin can already see in the CRM.
- It is sorted by one explicit figure (clients onboarded by default), never a derived rank. Every enabled agent appears, with zeros if they did nothing; a disabled agent appears only with figures in the window.

## Rationale

Every dropped figure was dropped for the same reason: its label would claim more than the data supports. "AUM" is the clearest case: an opening deposit and a balance differ by every transaction since. Time-to-verify is the closest call, but its error is silent and it would be published against a person's name. A composite score encodes a judgement about what matters that nobody has been asked to make.

Refusing a cross-currency sum, rather than returning it, keeps a wrong number from looking right. The checks cost one query each.

## Consequences

### Positive

- Every published number is reproducible from one documented query in the owning service.
- The dashboard cannot mislead through naming.
- Definitions live in this table, and the aggregate contract ([`docs/api/dashboard-aggregates.yaml`](../api/dashboard-aggregates.yaml)) points here.

### Negative / Trade-offs

- A thinner first release than the data could superficially support, and the dropped figures need explaining when asked for.
- A second currency in the data takes the deposit figure off the dashboard until this decision changes.

## Security & Privacy Impact

- Staff performance data is shown to admins only and every view is audited.
- An agent learns no other agent's figure, only a median across at least five agents and the group's size.
- Figures are counts, sums and ratios. The only client details are the work-queue names and IDs, which the caller can already open.

## Cost Impact

None. The figures are a handful of indexed queries in existing databases. Two indexes are added: `clients(created_at)` and `accounts(opening_date)`; plus `transactions(transaction_date)` for admins' bank-wide figures.

## Operational Impact

A new figure needs a row here first. If the legal-entity scope ever changes, the currency rules change with it and need a superseding ADR.

## Validation

- Repository tests in client-service, account-service and the receiver check each figure against fixtures: deleted rows left out, Singapore day boundaries, window edges, and a refused or omitted sum across two currencies.
- Dashboard unit tests check the per-agent join and sort, and accounts per client.
- The team comparison: a repository test checks the group (a zero-count member in, a too-old and a deleted-only agent out) and the median; service tests check the preset windows, Singapore "today" and the hidden median below 5; dashboard tests check the unsupported-window panel and the second check on the median.
- Still to do: check the figures against a seeded dataset with at least five agents whose numbers differ.

## References

- [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md), [ADR-0035](ADR-0035-account-ownership-across-services.md), [ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md), [ADR-0037](ADR-0037-transactions-database-and-account-linking.md), [ADR-0039](ADR-0039-dashboard-aggregate-endpoints.md)
- `docs/feature-plans/business-dashboard_plan.md`
- CS301 project specification: Appendix 1 principle 10; Appendix 2 (attributes); NFR 12
