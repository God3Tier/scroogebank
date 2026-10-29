# ADR-0035: Account ownership across the client and account services

- Status: Accepted
- Decision date/time: 2026-10-08 13:25 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/client-profile-management/account-service-split`
- Related issue/PR: TBD (pull request from `task/client-profile-management/account-service-split` to `dev`)
- Supersedes: ADR-0006, decision 4 only (user-service's account-ownership check moves from client-profile-management-service to account-service; ADR-0006's other decisions are unchanged)
- Superseded by: ADR-0036 (decision 4 in part: deleting accounts and clients became soft deletes, so "has accounts" means accounts that are not deleted, not accounts that are not `closed`; decisions 1–3, 5 and 6 and the rest of decision 4 remain in force); [ADR-0060](ADR-0060-client-reassignment.md) (the condition that a client's owner never changes: an admin's reassignment now updates `owner_agent_id` with the client; the decisions themselves are unchanged)

## Context

[ADR-0017](ADR-0017-microservices-per-business-area.md) splits accounts out of `client-profile-management-service` into account-service, each with its own database. Two rules depended on clients and accounts sharing a database:

1. **An account belongs to an existing client.** The `fk_accounts_client` foreign key guaranteed it, with `ON DELETE RESTRICT` stopping a client with accounts from being deleted.
2. **An agent may touch only accounts of their own clients.** `RequireClientBodyOwner` and `RequireAccountOwner` checked this by reading `clients.created_by_agent_id`, joining from `accounts.client_id`.

Neither works across databases. ADR-0017 and `docs/database/schema.md` say only that the foreign key "is replaced by an API check when accounts are created, and by client-service refusing to delete a client who still has open accounts". This ADR decides how.

## Decision Drivers

- Agent scoping must hold: an agent never reads, opens or deletes another agent's accounts.
- No service reads another service's tables (ADR-0017).
- Reads should not depend on another service being up.
- The same rule should serve later consumers that need accounts by agent, such as the dashboard's account aggregates (ADR-0033).

## Options Considered

### Option A — Copy the owner onto the account at creation (chosen)

account-service asks client-profile-management-service for the client when an account is created, and stores the client's `created_by_agent_id` as `accounts.owner_agent_id`. Reads and deletes check ownership locally.

- Pros: one cross-service call per account created, none per read; reads survive a client-service outage; account aggregates can be scoped and grouped by agent in one query.
- Cons: a copied value, which is only correct while a client's owner never changes.

### Option B — Ask client-profile-management-service on every account request

- Pros: nothing is copied.
- Cons: every account read and delete depends on another service being up; agent-scoped lists need the agent's whole client list first; aggregation by agent cannot be done in account-service's database.

### Option C — A service credential that lets account-service read any client

- Pros: works without the caller's token, for example from a background job.
- Cons: account-service would hold more privilege than any caller, and would have to reimplement the ownership rule client-profile-management-service already enforces.

## Decision

**Option A.**

1. `accounts.owner_agent_id VARCHAR(128) NOT NULL` holds the Cognito `sub` of the client's owner. It is not a foreign key.
2. **Creating an account** calls `GET /api/clients/{client_id}` on client-profile-management-service with the **caller's own token**, so that service applies its ownership rule to the caller. A 200 supplies `created_by_agent_id`. A 404 (missing, or not the caller's) is returned as 404. Anything else, or no answer within 2 seconds, is returned as 503 and **nothing is created**. Admins go through the same check, because it is what proves the client exists. The stored owner is the client's owner, never the caller.
3. **Reading or deleting an account** compares the token's `sub` with `owner_agent_id` locally. Admins skip the check. A mismatch returns 404, as before.
4. **Deleting a client** calls `GET /api/accounts?clientId={id}` on account-service with the caller's token. Any account refuses the delete with 409. Anything else, or no answer within 2 seconds, returns 503 and **nothing is deleted**. Until accounts can be closed instead of deleted, "has accounts" means any account row; afterwards it means any account not `closed`.
5. account-service keeps `source_service: CRM` for its audit events, so the Auditor needs no change.
6. **user-service's transaction read** ([ADR-0006](ADR-0006-transaction-account-linkage-and-access.md) decision 4) checks account ownership by calling `GET /api/accounts/{id}` on account-service instead of client-profile-management-service. The mechanism is unchanged: it forwards the caller's token and treats 404 as no access. This supersedes ADR-0006 decision 4 only.

**This depends on one condition: a client's owner never changes.** Nothing in the CRM updates `created_by_agent_id`, and there is no reassignment feature. If one is added, it must update every copy, for example by publishing an ownership-changed event on SQS that account-service consumes. That change supersedes this ADR.

## Rationale

Every request already carries the caller's verified token, so forwarding it gives the other service exactly the caller's authority and no more. Checks that cannot get an answer fail closed: a create or delete that cannot be verified is refused, because a wrongly created or orphaned account is harder to undo than a retried request.

Copying the owner costs one call when an account is opened, which already needed the client to be checked. It removes a cross-service call from every read, and lets account-service answer "which accounts does this agent own" in its own database, which the dashboard needs.

## Consequences

### Positive

- Account reads and deletes work while client-profile-management-service is down.
- Agent scoping stays with the services that own the data; no service reads another's tables.
- A client with accounts now returns 409. Before the split the foreign key violation surfaced as a 500.

### Negative / Trade-offs

- **Opening an account and deleting a client now need both services up.** Either one being down returns 503.
- **The client-delete check and the delete are not atomic.** An account opened for the client between the check and the delete is left pointing at a deleted client. The window is one request; nothing detects it today.
- **Deleting an account with transactions is blocked only while they share a database.** The receiver's `transactions.account_id` foreign key (`ON DELETE RESTRICT`) still blocks it locally. With separate databases nothing would, so the account-closing refactor or a delete check has to land before transactions move out. See Operational Impact.
- `owner_agent_id` is wrong if ownership is ever reassigned without updating it.
- The cross-service checks need the caller's token, so background jobs cannot use them (Option C remains the route if one ever must).

## Security & Privacy Impact

- Neither service holds more privilege than the caller: each cross-service call forwards the caller's own token.
- In production the base URLs must be `https://`; both services refuse to start otherwise, because the calls carry access tokens.
- `owner_agent_id` is a Cognito `sub`, already present in every token the agent presents and in `clients.created_by_agent_id`. It adds no new personal data.
- 404 for both missing and foreign resources is kept, so agents cannot enumerate other agents' clients or accounts.

## Cost Impact

None beyond ADR-0017's separate account-service and database.

## Operational Impact

- account-service needs `CLIENT_SERVICE_BASE_URL`; client-profile-management-service needs `ACCOUNT_SERVICE_BASE_URL`. Both are required at startup.
- user-service's transaction read now checks ownership against account-service (`ACCOUNT_SERVICE_BASE_URL`), not client-profile-management-service.
- **Locally, accounts still share the SFTP stack's `crm` database with clients and transactions**, because the receiver's `transactions.account_id` foreign key and linking trigger ([ADR-0006](ADR-0006-transaction-account-linkage-and-access.md)) need `accounts` in the same database. Deployed with a database per service, that foreign key and trigger cannot work. Replacing them is Feature 3 work, and needs a decision superseding ADR-0006.

## Validation

- Unit tests: the client lookup and account lookup clients against stub HTTP servers (200, 404, other status, timeout, unreadable body); account creation stores the client's owner, not the caller; client deletion refuses with accounts and fails closed without an answer.
- Database tests: `RequireAccountOwner` against the account table; the agent-scoped account list filters by `owner_agent_id`.
- End to end: agent B cannot open, read or delete an account on agent A's client; an admin opening an account on A's client leaves A as the owner; deleting a client with an account returns 409, and returns 503 with account-service stopped.

## References

- [ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0006](ADR-0006-transaction-account-linkage-and-access.md), [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md)
- [Open item 6: splitting accounts out](../architecture/open-items.md#6-splitting-accounts-out-of-client-profile-management-service)
- `services/account-service/README.md`, `services/client-profile-management-service/README.md`
