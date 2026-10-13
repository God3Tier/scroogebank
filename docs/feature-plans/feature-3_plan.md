# Feature 3 — SFTP Transaction Integration

## Plan metadata

- Status: In progress
- Primary developer: Joseph Yau
- Current working branch: `transaction-integration`
- Last updated: 2026-10-13
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
| F3-DB-01 | Give transactions their own database: replace the receiver's account foreign key and trigger with a lookup through account-service at import (client-credentials token), and drop the shared local `crm` database ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)) | Neo Jia Wen (agreed with Joseph Yau) | `services/sftp/receiver` (data, poller, `cronjob/accounts`), `services/sftp` Compose and Makefile, account-service `/internal/accounts/lookup` and service-token middleware | Cognito resource server `crm-accounts` and app client `transaction-ingest` | `task/transaction-integration/separate-transactions-db` | Implemented; unit and database tests pass; end-to-end checks not run |
| F3-DASH-01 | Serve the business dashboard's transaction figures: a private `POST /aggregates/transactions/stats` on the receiver, and `GET /aggregates/transactions/stats` on user-service scoping an agent to their own accounts; index on `transactions(transaction_date)` ([ADR-0039](../adr/ADR-0039-dashboard-aggregate-endpoints.md)) | Dashboard developer (review: Joseph Yau) | `services/sftp/receiver/src/{internal,serverd}`, `data/init.sql`; `services/user-service` `TransactionStatsController`, `TransactionStatsProxy` | ADR-0039 agreed with Joseph Yau | `task/business-dashboard/transaction-stats` | Implemented; unit and database tests pass; review and end-to-end checks pending |
| F3-ING-01 | Import fixes from the ADR-0024 review: audit each failure once per episode with `occurred_at`; move only a Pending status forward when a transaction is re-sent; stop starting files near a poll's deadline; `DB_SSLMODE` and `DB_SSLROOTCERT`; Lambda requirements in the receiver README ([ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decisions 3–5) | Neo Jia Wen (review: Joseph Yau) | `services/sftp/receiver` (`cronjob/poller`, `cronjob/sqs`, `internal/repository`, both `main.go`, README) | ADR-0024 amendments on `task/governance/ingestion-audit-events` | `task/transaction-integration/ingest-audit-fixes` | Implemented; unit and database tests pass; review and end-to-end checks pending |
