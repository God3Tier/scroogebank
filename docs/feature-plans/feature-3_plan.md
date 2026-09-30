# Feature 3 — SFTP Transaction Integration

## Plan metadata

- Status: In progress
- Primary developer: Joseph Yau
- Current working branch: `transaction-integration`
- Last updated: 2026-09-30
- Related decision: [ADR-0006](../adr/ADR-0006-transaction-account-linkage-and-access.md)

## Goal

Import external SFTP transactions, link matched records to CRM account UUIDs, and provide an authenticated API for Admins and Agents to view transactions within their account access scope.

## Scope

- Use the shared CRM PostgreSQL database for client, account, and transaction data.
- Store a resolved `account_id` foreign key for each transaction whose source `account_id` matches a CRM account UUID. Keep unmatched source references for reconciliation, with a null CRM account link.
- Expose the public transaction read route through user-service. User-service authenticates the Cognito access token and enforces the Admin/Agent role.
- Delegate account ownership verification to the authenticated client-profile account endpoint. Agents can read their own clients' accounts; Admins can read any account.
- Keep the receiver HTTP route private and unauthenticated. The receiver repository uses SQLBoiler to return rows linked to the requested account.

## Acceptance criteria

- Imported records matching an existing CRM account UUID have `transactions.account_id` set and reference `accounts.id`.
- Unmatched source references remain unlinked and are not returned by account transaction reads.
- `GET /api/accounts/{accountId}/transactions` on user-service requires a valid Cognito access token and an `ADMIN` or `AGENT` group.
- The request passes only after client-profile confirms the account is accessible to that caller.
- Admins can request any account; Agents cannot retrieve transactions for accounts owned by another Agent.
- The internal receiver has no token or role middleware and is reachable only on a trusted internal path.
- Feature plan, schema migration, API behavior, local run instructions, and audit behavior remain documented.

## Tasks

| Task | Description | Owner | Expected files/components | Dependencies | Branch | Status |
|---|---|---|---|---|---|---|
| F3-READ-01 | Link transaction rows to CRM account UUIDs and retrieve through SQLBoiler | Joseph Yau | `services/sftp/receiver/data`, repository, `serverd`, Compose | Shared CRM schema | `transaction-integration` | In progress |
| F3-READ-02 | Add authenticated User-service route; verify account ownership through client-profile before proxying to receiver | Joseph Yau | `services/user-service`, client-profile account repository/controller/router | Cognito app client and internal service URLs | `transaction-integration` | In progress |
