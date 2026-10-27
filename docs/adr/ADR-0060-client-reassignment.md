# ADR-0060: Admins reassign clients to another agent

- Status: Accepted
- Decision date/time: 2026-10-23 14:03 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/base-features/client-reassignment`
- Related issue/PR: TBD (pull request from `task/base-features/client-reassignment` to `dev`)
- Supersedes: [ADR-0035](ADR-0035-account-ownership-across-services.md)'s condition that a client's owner never changes (decision 1's copied owner is now updated when the client is reassigned; decisions 2 to 6 are unchanged)
- Superseded by: N/A

## Context

A client belongs to exactly one agent (requirements Q&A CPM30), and no two agents handle the same client (UM07). The Q&A gives admins one write on client data: when an agent leaves, is disabled or is deleted, an admin assigns their clients to another agent.
- **UM07:** "If Agent leave the organization then Admin will assing new agent to respective client."
- **UM15:** "Admin will assign the client to new agent."
- **UM17:** "Admin will assign a new agent to all the clients handled by earlier agent."
- **UM18:** "Admin re-assign new agent."

Nothing implements it ([open item 24](../architecture/open-items.md#24-client-reassignment)). [ADR-0059](ADR-0059-admins-read-only-on-client-data.md) made admins read-only on client data and reserved reassignment as the one admin write, on a route of its own.

Who owns a client is stored in several places:
- client-profile-management-service: `clients.created_by_agent_id`, the Cognito `sub` of the agent who created the client. Every ownership check, the client list and the dashboard's client figures read it.
- account-service: `accounts.owner_agent_id`, copied from the client when an account is opened. ADR-0035 depends on "a client's owner never changes".
- engagement-service: each meeting, reminder and follow-up draft belongs to the agent who made it (`owner_agent_sub`), with the client's ID.
- client-health-monitor-service: an open task records the agent it was assigned to; its assignment lookup is not built yet.
- event-alert-service: holdings come from synthetic fixtures with their own agent, not from the CRM.

## Decision Drivers

- Follow the Q&A: an admin moves all of a leaving agent's clients (UM17) or a chosen client (UM07).
- A client and its accounts must never end up with different agents.
- Only an enabled agent can receive clients; an admin cannot (UM01, UM08).
- Each moved client is audited with the agent before and after.
- No new service credential, and no new IAM permission.
- Small enough to build and test before the next deployment.

## Options Considered

### Where the agent is stored

- **Option A — Rename `created_by_agent_id` to `assigned_agent_id` and overwrite it (chosen).** Every query that scopes by owner keeps working with the new name. Who created the client is still in the audit log's `CREATE` event. Every deployment starts from an empty database ([ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md)), so the rename is made in the `V1` schema, with no migration.
- **Option B — Keep `created_by_agent_id` and add `assigned_agent_id`.** Keeps the creator in the row, but every ownership query, the list and the aggregates must move to the new column anyway.
- **Option C — Overwrite `created_by_agent_id` and keep its name.** No code change, but the name would then say the wrong thing.

### How the accounts' copies follow

- **Option D — client-service calls account-service inside its own transaction (chosen).** client-service locks the clients, asks account-service to set the new owner on their accounts, then updates the clients and commits. If account-service does not confirm, nothing is committed.
- **Option E — An ownership-changed event on SQS that account-service consumes**, as ADR-0035 suggested. Survives an account-service outage, but needs a queue, a consumer and retries, and leaves a window where the client and its accounts disagree.
- **Option F — account-service stops copying the owner and asks client-service every time.** ADR-0035's Option B, rejected there: every account read would depend on client-service.

### Checking the new agent

- **Option G — client-service asks user-service, forwarding the admin's token (chosen).** `GET /api/users/{id}` is admin-only and returns the user's Cognito `sub`, role and whether they are enabled.
- **Option H — client-service asks Cognito directly.** Needs a new IAM permission and duplicates what user-service already knows.
- **Option I — trust the frontend's choice.** A wrong ID would hand clients to nobody.

## Decision

**Options A, D and G.**

1. **Column.** `clients.created_by_agent_id` becomes `clients.assigned_agent_id`, the Cognito `sub` of the client's agent, in the `V1` schema. The client profile's JSON field is renamed the same way. Creating a client sets it to the creating agent, as before. Nothing else changes it except a reassignment.
2. **Endpoint.** `POST /api/clients/reassignments`, admins only (403 for anyone else), with a body of:
   - `to_user_id`: the user-service ID of the agent who receives the clients; and exactly one of
   - `from_user_id`: move **all** of this user's clients that are not deleted (UM17), or
   - `client_ids`: move **these** clients, whoever holds them (UM07), 1 to 100 distinct IDs.

   It answers 200 `{"reassigned": n, "client_ids": [...]}`; `n` may be 0. At most 1,000 clients move in one request; more is 409, and nothing moves.
3. **Checks.** client-service looks up `to_user_id` (and `from_user_id`) with `GET {USER_SERVICE_BASE_URL}/api/users/{id}`, forwarding the admin's token. The receiver must be an **enabled AGENT**, and not the agent the clients come from; otherwise 400. An unknown user is 404. If user-service does not answer within 2 seconds, 503 and nothing moves. With `client_ids`, a missing or deleted client is 404 and nothing moves; a client already with the receiver is left out of the count.
4. **Order.** In one database transaction, client-service:
   1. locks the clients that move (`SELECT … FOR UPDATE`);
   2. calls `PUT {ACCOUNT_SERVICE_BASE_URL}/ownership/accounts` on account-service with `{"client_ids": [...], "owner_agent_id": "<sub>"}` and the admin's token; account-service sets `owner_agent_id` on every account of those clients, deleted ones included, and answers with the count;
   3. sets `assigned_agent_id` and `updated_at` on the clients, and commits.

   If account-service does not answer 200 within 2 seconds, the transaction is rolled back and the answer is 503. If the commit fails after account-service answered, the accounts already have the new owner and the clients do not: the admin repeats the request, which sets the same owner again and then moves the clients.
5. **account-service's route** sits outside `/api`, so API Gateway never routes it, like `/aggregates`. It takes a user access token and is for admins only. It is not audited there: client-service audits the reassignment.
6. **Audit.** client-service saves one audit event for each client moved, as action `UPDATE` on the reassignment route, with `client_id`, `attribute_name: "assigned_agent_id"`, `before_value` (the agent before) and `after_value` (the receiver). A refused request is saved once, with its status code, as before. The activity feed shows each moved client as "reassigned a client".
7. **Engagement data stays with the old agent.** engagement-service is not told. The old agent's meetings and reminders stay in their calendar. Their unsent follow-up drafts for a moved client can no longer be sent, because the client lookup now answers 404 to them. The new agent starts with none.
8. **Frontend.** On Admin Home, each agent in **Manage Agents** has **Reassign clients**, which moves all their clients to another enabled agent. An admin's client page shows the client's agent and has **Reassign**, which moves that one client.

## Rationale

Renaming the column keeps every ownership query as it is, and makes the name match what it now means. The teardown rule makes it free to do in `V1`.

Calling account-service while the clients are locked keeps the client and its accounts together without a queue. The one failure it leaves, a commit failing after the accounts moved, is put right by repeating the request, because setting an owner is idempotent. Forwarding the admin's token to user-service and account-service gives each one exactly the admin's authority, as ADR-0035 does for opening accounts, so no service gets a new credential.

## Consequences

### Positive

- Admins can do what UM07, UM15 and UM17 ask, from Admin Home or the client's page.
- A moved client's accounts move with it in the same request, and every ownership check, list and dashboard figure follows at once.
- Every move is audited per client, with the agent before and after.

### Negative / Trade-offs

- Reassigning needs client-service, user-service and account-service all up.
- The column no longer says who created the client; only the audit log does.
- The dashboard's "clients onboarded" by agent ([ADR-0038](ADR-0038-dashboard-metric-definitions.md)) counts a moved client for its new agent.
- The old agent's unsent drafts for a moved client are stranded; their future meetings are not cancelled.
- client-health-monitor-service rejects a new finding for a client whose open task belongs to the old agent ("Active task belongs to a different Agent"). Its owners must decide what reassignment does to open tasks when they build the assignment lookup.
- Changing an agent with clients to an admin (`PATCH /api/users/{id}/role`), or disabling one, is not blocked. The admin must reassign their clients, before or after.

## Security & Privacy Impact

- The admin's role comes from the verified token; client-service, user-service and account-service each check it.
- account-service's new route is reachable only inside the VPC and only with an admin's token.
- Audit events hold Cognito `sub`s and client IDs, no personal data.
- A wrong receiver is caught before anything moves: the receiver must be an enabled agent.

## Cost Impact

None.

## Operational Impact

- client-service needs `USER_SERVICE_BASE_URL` (https in production) and a security-group rule from client-service to user-service on its port. Both are in `stacks/crm`.
- Deploy client-service, account-service and the frontend together. The `V1` schema change needs an empty clients database, which every deployment starts with.

## Validation

- **Unit tests, client-service:** the request rules (exactly one of `from_user_id` and `client_ids`, distinct IDs, the limits); the receiver must be an enabled agent and not the old one; user-service and account-service failures are 503 with nothing moved; the repository against PostgreSQL moves only the selected clients, rolls back when account-service fails, and skips deleted clients; one audit event per moved client with the agent before and after; the route refuses agents.
- **Unit tests, account-service:** the route refuses agents; the repository sets the owner on every account of the clients and only those.
- **Unit tests, frontend:** both Reassign controls send the right body and say how many clients moved; the activity feed describes a reassignment.
- **End to end (not yet run):** agent A creates a client and opens an account; an admin reassigns A's clients to agent B; B sees the client and its account, A gets 404 for both; the activity feed shows the reassignment.

## References

- [ADR-0035](ADR-0035-account-ownership-across-services.md) (condition partly superseded), [ADR-0038](ADR-0038-dashboard-metric-definitions.md), [ADR-0051](ADR-0051-engagement-service-and-mail-dispatcher.md), [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md), [ADR-0059](ADR-0059-admins-read-only-on-client-data.md)
- Requirements Q&A rows UM01, UM07, UM08, UM15, UM17, UM18, CPM30 and CPM31
- [Open item 24: client reassignment](../architecture/open-items.md#24-client-reassignment)
- `services/client-profile-management-service/README.md`, `services/account-service/README.md`, `frontend/README.md`, `docs/feature-plans/feature-2_plan.md`

## Revision history

- 2026-10-23: proposed.
- 2026-10-23 14:03 +08:00: accepted by the decision owner.
