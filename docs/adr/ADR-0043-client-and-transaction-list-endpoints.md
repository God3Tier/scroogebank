# ADR-0043: List endpoints for clients and for all transactions

- Status: Proposed
- Decision date/time: 2026-10-16 11:15 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen; Joseph Yau (owner of the Feature 2 and Feature 3 code these endpoints are added to; agreement pending)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/base-features/list-endpoints`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: ADR-0064 in part (decisions 2 and 3 for the transaction list: transaction-service serves and audits `GET /api/transactions` itself)

## Context

The frontend still shows seed data on the agent and admin dashboards, because two reads have no endpoint:

1. **A list of clients.** client-service serves one client at a time (`GET /api/clients/{id}`). An agent cannot see which clients they have, and an admin cannot see any client without already knowing its ID.
2. **A list of all transactions.** Transactions can be read only per account (`GET /api/accounts/{accountId}/transactions`, through user-service, [ADR-0006](ADR-0006-transaction-account-linkage-and-access.md)). The admin dashboard's "All Transactions" table has nothing to read.

The requirements Q&A allows both reads:

- **UM04:** "Admin have read only access to client info."
- **TM14:** "Admin can see client transaction… admin has a overall view of what's going on."
- **CPM31:** "Only Agent hand all client related activities. Admin has read access of it."

[ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) decision 4 already lets admins access all clients, and agents only their own.

## Decision Drivers

- An agent must never see another agent's clients, and must have no parameter that widens their view.
- Every read of client or transaction data is audited, as the per-client and per-account reads are today.
- Follow the existing patterns: client-service scopes by the verified caller; the SFTP receiver stays private and unauthenticated, with user-service as the boundary ([ADR-0006](ADR-0006-transaction-account-linkage-and-access.md) decision 5).
- Bounded responses: neither list returns an unbounded number of rows.

## Options Considered

### Admin transaction list

1. **No list; point admins to the business dashboard's transaction totals.** No new endpoint, but admins cannot see individual transactions, which TM14 expects.
2. **List through user-service, admin only (chosen).** Same boundary as the per-account read.
3. **Let the frontend fetch every account's transactions.** One request per account; does not scale and needs an account list across all clients.

### Paging

- **Clients:** page and size, like user-service's `GET /api/users`. The list is small per agent, and a total count drives the dashboard's "My Clients" figure.
- **Transactions:** a cursor, like `GET /api/activities`. The table grows with every import, and keyset paging stays fast on the existing `idx_transactions_date` index; an offset would not.

## Decision

1. **`GET /api/clients?page&size`** in client-service lists clients that are not deleted, newest first (`created_at DESC, client_id`). An agent gets only clients whose `created_by_agent_id` is their token's `sub`; an admin gets every client. The scope comes from the verified token only; there is no owner parameter. `size` defaults to 20, maximum 100. The response is `{content, page, size, totalElements, totalPages}`, the same shape as user-service's `PageResponse`. Each entry has the same fields as `GET /api/clients/{id}`. There is no search: searching encrypted fields ([ADR-0030](ADR-0030-client-data-envelope-encryption.md)) is a separate decision. A client whose data key is gone, which is how ADR-0030 erases a client, is left out of the page instead of failing the whole list; it still counts in `totalElements`.
2. **`GET /api/transactions?cursor&size&status`** in user-service is for admins only; agents get 403. user-service forwards the query to a new private receiver route of the same path, which returns transactions newest first (`transaction_date DESC, transaction_id DESC`) with `{content, nextCursor}`; the cursor is the last row's date and ID. `size` defaults to 20, maximum 100. `status` optionally filters on `Completed`, `Pending` or `Failed`. Each entry includes `account_id`, so the UI can open the account's client.
3. **Auditing.** client-service's existing request middleware audits the client list as a `GET` with no client ID. user-service audits the transaction list as `READ` of resource type `TRANSACTION`, with no resource ID, like the corrupted-log list. The auditor accepts that resource type and action from user-service.
4. **Admins only read through these endpoints.** Neither endpoint changes data.

## Rationale

The client list reuses client-service's caller scoping (the same rule `RequireClientOwner` applies per client) inside the query, so no other agent's row is ever read. The transaction list keeps the receiver private and puts the role check in user-service, as for the per-account read. Cursor paging bounds the cost of the largest table.

## Consequences

### Positive

- The dashboards can show real clients and transactions, and admins get the overall view TM14 describes.
- No schema change and no new service.

### Negative

- The client list decrypts every row it returns. Each client has its own data key, so a page of 100 may need up to 100 key unwraps; the existing data-key cache limits repeats.
- A page can hold fewer entries than `size` when it contains erased clients, and `totalElements` still counts them, until erasure also removes or soft-deletes the row ([open item 21](../architecture/open-items.md#21-erasing-soft-deleted-client-data)).
- The admin transaction list leaves out imported transactions not linked to an account (`account_id` null), as the per-account read does. Admins see those only as the dashboard's unlinked count.

## Security & Privacy Impact

- Agents cannot widen the client list: the owner filter comes from the token. Tests cover an agent seeing only their own clients.
- The admin transaction list exposes every linked transaction to admins. That matches TM14 and is audited per request.
- No new route reaches the receiver from outside: API Gateway and the ALB send `/api/transactions` to user-service only.

## Cost Impact

None beyond request volume. Possible extra KMS calls when listing clients, bounded by the page size and the data-key cache.

## Operational Impact

- The crm stack's ALB needs `/api/transactions` added to user-service's rule (Terraform, on the infrastructure branch).
- The auditor must be deployed with the new resource type before user-service, or the new audit events are rejected.

## Validation

- client-service: controller, service and repository tests, including a database test that an agent sees only their own clients and that deleted clients are left out.
- Receiver: handler and repository tests for ordering, cursor and status filter.
- user-service: controller tests for 403 as agent and 200 as admin, and that the call is audited.
- Auditor: payload validation test for `TRANSACTION` `READ` without a resource ID.

## References

- Requirements Q&A: UM04, TM14, CPM31.
- [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md), [ADR-0006](ADR-0006-transaction-account-linkage-and-access.md), [ADR-0030](ADR-0030-client-data-envelope-encryption.md), [ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md), [ADR-0037](ADR-0037-transactions-database-and-account-linking.md).
