# ADR-0039: Business dashboard aggregate endpoints: private routes, scoped by the caller's token, audited once by the dashboard

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-10-09 12:30 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen; Joseph Yau (owner of the Feature 2 and Feature 3 code these endpoints are added to; agreement pending)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/business-dashboard`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: ADR-0064 in part (decisions 4 and 5, and decision 2's exception for the receiver: transaction-service serves `GET /aggregates/transactions/stats` itself, scoped by the caller's token)

## Context

[ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md) has dashboard-service fan out to aggregate endpoints on the services that own the data, forwarding the caller's JWT, with each service scoping its own answer. It leaves three questions open:

1. **Where the endpoints live.** API Gateway routes `/api/clients/*`, `/api/accounts/*` and `/api/users/*` to their services, and each service audits its `/api` reads.
2. **How transaction totals are scoped.** The transactions live in the SFTP receiver, which plays transaction-service's query role. It has no authentication of its own ([ADR-0006](ADR-0006-transaction-account-linkage-and-access.md) decision 5): user-service checks the caller with account-service before reading one account's transactions ([ADR-0035](ADR-0035-account-ownership-across-services.md) decision 6).
3. **What the dashboard asks for.** The figures themselves are defined in [ADR-0038](ADR-0038-dashboard-metric-definitions.md).

## Decision Drivers

- No network trust: every endpoint checks the caller's token.
- An agent must never see another agent's figures, and must have no parameter that widens their view.
- One audit record per dashboard view, without filling the hash chain with duplicates.
- Change the Feature 2 and 3 services as little as possible, in ways their owner can review separately.

## Options Considered

### Route placement

**A. Outside `/api`, e.g. `GET /aggregates/clients/stats` (chosen)**

- Only services inside the VPC can call them, because API Gateway never routes `/aggregates`. The caller's token is still checked.
- One `DASHBOARD_VIEW` event per view, from the dashboard. The owners' audit code is unchanged.
- Gap: the dashboard's audit is best-effort (ADR-0033), so a failed send loses the only record of that view.
- Precedent: ADR-0037's `/internal` lookup sits outside `/api` for the same reason.

**B. Under `/api`, behind each owner's audit middleware**

- Anyone with a token can call them through API Gateway.
- Each view writes `DASHBOARD_VIEW` plus a CRM `GET` from client-service and account-service: 3–4 times the events per view.
- client-service's audit maps the call to `GET` with no resource ID, and its payload (attribute, before and after values) describes nothing useful about a count.
- Reads served from the dashboard's cache never reach the owners, so their events would be incomplete anyway.

### Scoping an agent's transaction totals

**A. user-service in front (chosen).** user-service checks the token, asks account-service for the agent's account IDs, and asks the receiver for totals over those accounts. The same pattern as one account's transactions today; the receiver stays private with no login check.

**B. The receiver checks tokens.** A third copy of the Cognito verifier, and it reverses ADR-0006 decision 5.

**C. Copy each account's owner onto its transactions at import.** The receiver could then filter by owner, but someone still has to verify the token, and ADR-0037 left the copy out on purpose.

## Decision

**Route placement A and transaction scoping A.**

1. **Endpoints** (contract: [`docs/api/dashboard-aggregates.yaml`](../api/dashboard-aggregates.yaml)):

   | Service | Endpoint | Returns |
   |---|---|---|
   | client-service | `GET /aggregates/clients/stats?from&to[&groupBy=agent]` | Client figures; per-agent rows for admins who ask |
   | client-service | `GET /aggregates/clients/action-items[?staleDays]` | The caller's stale unverified clients |
   | client-service | `GET /aggregates/clients/team-comparison[?days=7\|30\|90]` | The caller's clients onboarded against the team median, with no other agent's figure ([ADR-0038](ADR-0038-dashboard-metric-definitions.md)) |
   | account-service | `GET /aggregates/accounts/stats?from&to[&groupBy=agent]` | Account figures; 409 when deposits would cross currencies |
   | account-service | `GET /aggregates/accounts/ids` | The IDs of the accounts the caller owns |
   | user-service | `GET /aggregates/transactions/stats?from&to` | Transaction figures, scoped as below |
   | receiver | `POST /aggregates/transactions/stats` | Transaction figures for `{"all": true}` or `{"accountIds": [...]}` |

2. **Private routes.** `/aggregates` sits outside `/api`, so API Gateway never routes it; dashboard-service reaches the services over Service Connect. Every route except the receiver's requires a user access token with the `ADMIN` or `AGENT` group, checked by the service itself. `/internal` keeps meaning "service tokens only".
3. **Scope comes from the token.** Admins get every row, agents their own (ADR-0038). Per-agent grouping is admin-only and returns 403 otherwise. No endpoint takes an agent ID, and unknown parameters are ignored.
4. **Transaction totals go through user-service.** For an admin it asks the receiver for `all`; for an agent it forwards the token to account-service's `/aggregates/accounts/ids` and passes those IDs on. The caller's token never reaches the receiver.
5. **The receiver stays private and unauthenticated**, like its existing read. It refuses a request that names neither `all` nor `accountIds`, or both, so "everything" is never a default. An empty account list returns zeros without a query.
6. **The owners do not audit these endpoints.** The dashboard's `DASHBOARD_VIEW` event, sent for every view including cache hits, is the record (ADR-0033). user-service audits only `/api/users/**` and `/api/accounts/**`, so `/aggregates` is outside it too.
7. **Windows** are validated by every service as well as the dashboard: dates only, forwards, at most 366 days counting both ends (the dashboard allows 365, so its own limit is the one users meet).

## Rationale

Every permission check still happens in the service that owns the data, because the token is checked there. Only the audit of a view relies on the route being private, and the dashboard audits every view anyway. Putting the routes under `/api` would add duplicate events to the hash chain without closing that gap.

user-service already sits in front of the receiver for single-account reads, with account-service deciding ownership, so the transaction totals reuse a reviewed pattern instead of adding a verifier to the receiver.

## Consequences

### Positive

- One audit event per view; the owners' audit code is unchanged.
- No new credential or verifier; the receiver gains no public surface.
- The endpoints are additive: no existing route or response changes.

### Negative / Trade-offs

- A direct call to an `/aggregates` route from inside the VPC is not audited. It still needs a valid user token and returns only the caller's own figures.
- An agent's transaction totals take two hops (account-service, then the receiver) and fail if either is down. The panel then shows as unavailable.
- An agent with many accounts sends a long ID list to the receiver; it accepts up to 20,000.
- The window and scope rules are repeated in four services, in two languages.

## Security & Privacy Impact

- No network trust: each route checks the caller's token, except the receiver's, which is private and is only called by user-service after its check (as ADR-0006 decision 5 already allows).
- Agents cannot widen their scope: tests in client-service, account-service, user-service and the dashboard pass forged `agentId`, `admin` or `all` parameters and check that they change nothing.
- Responses are counts, sums and ratios, plus the caller's own work-queue clients.

## Cost Impact

None beyond ADR-0033: a few indexed queries per cache miss, and three indexes (ADR-0038).

## Operational Impact

- dashboard-service needs `USER_SERVICE_BASE_URL`, `CLIENT_SERVICE_BASE_URL` and `ACCOUNT_SERVICE_BASE_URL`; user-service already has `ACCOUNT_SERVICE_BASE_URL` and `SFTP_RECEIVER_BASE_URL`.
- **Infrastructure requirements** for later:
  - Service Connect from dashboard-service to user-service, client-service and account-service;
  - API Gateway must not route `/aggregates/*`.

## Validation

- Router tests in client-service and account-service: the caller comes from the token only, admins are marked, and callers without a CRM role or token are refused.
- user-service controller tests run the real token filter: an agent is scoped as an agent whatever the parameters say, and the view is not audited there.
- user-service proxy tests: an admin's request skips account-service, an agent's carries only their account IDs, and the token never reaches the receiver.
- Receiver handler tests: a request without exactly one scope, with a non-UUID account ID, or with a bad window is refused.
- **End to end (not yet run):** with all services and real tokens, agent A's dashboard counts only A's figures.

## References

- [ADR-0006](ADR-0006-transaction-account-linkage-and-access.md), [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md), [ADR-0035](ADR-0035-account-ownership-across-services.md), [ADR-0037](ADR-0037-transactions-database-and-account-linking.md), [ADR-0038](ADR-0038-dashboard-metric-definitions.md)
- [`docs/api/dashboard-aggregates.yaml`](../api/dashboard-aggregates.yaml), [`docs/api/dashboard-api.yaml`](../api/dashboard-api.yaml)
- [Security model](../security/security-model.md)
