# ADR-0059: Admins have read-only access to client data

- Status: Accepted
- Decision date/time: 2026-10-23 12:52 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/base-features/admin-read-only-client-data`
- Related issue/PR: TBD (pull request from `task/base-features/admin-read-only-client-data` to `dev`)
- Supersedes: [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) decision 4 in part (admins read every client profile and account, but do not change them); [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md) decision 1 in part (admins list and view every client's documents, but only agents upload and verify); [ADR-0050](ADR-0050-accounts-pending-until-client-verified.md) decision 7 (admins no longer open or delete accounts)
- Superseded by: N/A

## Context

ADR-0005 decision 4 says "Admins can access all client profiles and their accounts", and the code reads "access" as everything. `RequireClientOwner` in client-profile-management-service and `RequireAccountOwner` in account-service let every admin through, and `POST /api/clients` and `POST /api/accounts` check only that the caller is an admin or an agent. So an admin can create, update, delete and verify clients, upload their identity documents, and open and delete accounts. The client page offers admins the same controls as agents.

The requirements Q&A (`Project Requirements Q&A-2627-t1.xlsx`) says otherwise:
- **UM04:** "Admin have read only access to client info".
- **UM18:** "Admin dont have write access on client related activities. Admin can't create new client account, update profile etc."
- **CPM24** (follow-up rows): "client account can only be managed by agent. admin can only manage agent".
- **CPM31:** "Only Agent hand all client related activities. Admin has read access of it."
- **UM01, UM08:** admins, the root admin included, cannot have clients.

The brief agrees: agents create client profiles and accounts, and admins handle "Account Management for the CRM" (the CRM's user accounts) and "can see the activities of all agents". ADR-0050 already made account status changes agent-only, following UM18, and left the rest as [open item 23](../architecture/open-items.md#23-admin-writes-to-client-data).

UM07, UM15 and UM17 add one admin task on client data: when an agent leaves, is disabled or is deleted, an admin assigns their clients to another agent. Nothing implements it ([open item 24](../architecture/open-items.md#24-client-reassignment)).

## Decision Drivers

- Follow the Q&A: admins read client data and do not change it (UM04, UM18, CPM24, CPM31).
- Admins own no clients (UM01).
- The rule must hold whoever calls the API, not only in the frontend.
- Admins keep everything they read today: the activity feed's "View client", profiles, documents, accounts and transactions.
- Leave room for client reassignment, the one admin write the Q&A asks for.

## Options Considered

### Option A — Keep today's access

- Pros: no change.
- Cons: contradicts UM01, UM04, UM18, CPM24 and CPM31. An admin can create a client and becomes its owner, which UM01 forbids.

### Option B — Hide the write controls from admins in the frontend only

- Pros: small; no service change.
- Cons: the API still accepts admins' writes, so the rule depends on the frontend. ADR-0050 keeps the frontend a convenience, not the control.

### Option C — Refuse admins' writes in both services, and hide the controls (chosen)

- Pros: the rule holds for every caller; one check per write route; reads unchanged.
- Cons: a disabled agent's clients cannot be changed by anyone until reassignment exists.

## Decision

**Option C.**

1. **Reads.** Admins keep read access to every client, as today: the client list, a profile, its document list and each document in client-profile-management-service; an account list and one account in account-service; transactions through user-service. Agents still see only their own clients and accounts, and other agents' get 404.
2. **Writes are agent-only.** Each service refuses a caller who is not an agent with **403** `admins have read-only access to client data` on:
   - client-profile-management-service: `POST /api/clients`, `PUT /api/clients/{clientId}`, `DELETE /api/clients/{clientId}`, `POST /api/clients/{clientId}/verify`, `POST /api/clients/{clientId}/documents`;
   - account-service: `POST /api/accounts`, `DELETE /api/accounts/{accountId}`, `PATCH /api/accounts/{accountId}` (ADR-0050 already refuses admins there).
   The check is a route middleware, `RequireAgent`, that runs before the ownership check, so an admin's write never reaches the controller. account-service's `CreateAccount` also refuses admins itself, as `UpdateAccountStatus` does, with 403 `admins do not manage client accounts`.
3. **The client page** shows admins the profile, the documents (which they can open), the verification and a link to the accounts page, with a note that only the client's agent can change them. It shows them no Edit, Open account, Upload or Verify control. The accounts page already shows admins statuses only (ADR-0050).
4. **Audit.** A refused write is audited like any other call, with its 403 status code, and stays out of the activity feed, as refused requests already do.
5. **Client reassignment** ([open item 24](../architecture/open-items.md#24-client-reassignment)) will be the one admin write on client data, and will be added later in an ADR of its own. That ADR must amend [ADR-0035](ADR-0035-account-ownership-across-services.md), whose account owner is copied once and never changes, decide what happens to the old agent's meetings, reminders and drafts in engagement-service, and say how the change is audited. This ADR does not block it: reassignment will get its own admin-only route.

## Rationale

The Q&A answers the question five times, the same way. Checking the role at the route, in both services, makes the API the control and keeps the frontend change cosmetic. A middleware, rather than a check in each controller, puts the rule in one visible place in each router. Reads are unchanged, so the activity feed, the dashboards and account-service's client lookup (which forwards the caller's token to `GET /api/clients/{id}`) work as before.

## Consequences

### Positive

- The CRM follows UM01, UM04, UM18, CPM24 and CPM31.
- Every client and account change is made by the client's agent, so the audit trail always names that agent.
- An admin can no longer create a client and own it.

### Negative / Trade-offs

- Until reassignment exists, a disabled or deleted agent's clients and accounts cannot be changed by anyone.
- Admins lose the ability to fix a client's data for an absent agent.
- Bruno or scripted requests that wrote client data with an admin token now get 403.

## Security & Privacy Impact

- The role comes from the verified Cognito token's `cognito:groups`; a request cannot claim to be an agent.
- `RequireAgent` runs before `RequireClientOwner` and `RequireAccountOwner`. An admin gets 403 on every write without a database read. An agent's write still goes through the ownership check, so another agent's client or account is still 404, and a 403 tells an admin nothing they cannot already read.
- Fewer roles can change client personal data.

## Cost Impact

None.

## Operational Impact

- Deploy both services and the frontend together. An older frontend still shows admins the write controls, and the services refuse them with 403.
- No data migration. Clients and accounts that admins created earlier keep their owner.

## Validation

- **Unit tests, both services:** `RequireAgent` lets an agent through and refuses an admin and a caller without a role; through the router, an admin's write never reaches the controller and gets 403, an admin's reads still reach it, and an agent's routes are unchanged.
- **Unit tests, account-service:** `CreateAccount` refuses an admin without looking up the client or writing, and the controller answers 403.
- **Unit tests, frontend:** an admin sees the profile, the documents and the read-only note, can open a document, and has no Edit, Open account, Upload or Verify control.
- **End to end (not yet run):** with an admin token, `GET /api/clients/{id}` returns 200 and `PUT /api/clients/{id}`, `POST /api/accounts` and `DELETE /api/accounts/{id}` return 403; an agent's flows are unchanged.

## References

- [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) (decision 4, partly superseded), [ADR-0035](ADR-0035-account-ownership-across-services.md), [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md) (decision 1, partly superseded), [ADR-0050](ADR-0050-accounts-pending-until-client-verified.md) (decision 7, superseded)
- CS301 project specification v18.0, roles and Appendix 3; requirements Q&A rows UM01, UM04, UM07, UM08, UM15, UM17, UM18, CPM24 (with its follow-up rows) and CPM31
- [Open item 23: admin writes to client data](../architecture/open-items.md#23-admin-writes-to-client-data), [open item 24: client reassignment](../architecture/open-items.md#24-client-reassignment)
- `services/client-profile-management-service/README.md`, `services/account-service/README.md`, `docs/feature-plans/feature-2_plan.md`

## Revision history

- 2026-10-23 12:52 +08:00: proposed.
- 2026-10-23 13:11 +08:00: accepted by the decision owner.
