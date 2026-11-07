# ADR-0006: Link SFTP transactions to CRM accounts and serve reads through User Service

- Status: Accepted
- Decision date/time: 2026-09-30 15:46 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Joseph Yau
- AI-assisted draft: Yes — Codex
- Feature/branch: Feature 3 / `transaction-integration`
- Related issue/PR: Not yet assigned
- Supersedes: N/A
- Superseded by: ADR-0035 (decision 4 only: the account-ownership check moved from client-profile to account-service); ADR-0037 (decisions 1–2 only: transactions have their own database and are linked to accounts through account-service at import, not by a foreign key and trigger); ADR-0064 (decisions 3 and 5: transaction-service checks its own callers and is called directly, not through User-service). Decision 6 remains in force

## Context

The SFTP feed previously stored its `account_id` only as `source_account_ref` text. The receiver database did not contain CRM accounts or client ownership, while account ownership is defined by `accounts.client_id` and `clients.created_by_agent_id`. The transaction read endpoint also needed to follow the project's Cognito role and ownership rules without putting authentication middleware in the receiver.

## Decision

1. The SFTP receiver, client-profile service, and User-service use the shared CRM PostgreSQL database for transaction/account linkage. Imported `account_id` values are CRM account UUIDs.
2. `transactions.account_id` is a nullable foreign key to `accounts.id`. The receiver database trigger resolves the source reference to an account UUID. Unmatched references remain unlinked and are excluded from account transaction reads.
3. The public `GET /api/accounts/{accountId}/transactions` route is served by User-service. It authenticates the Cognito access token and requires the `ADMIN` or `AGENT` role.
4. User-service forwards the access token to client-profile's protected account read endpoint. Client-profile permits Admin access or verifies the Agent's ownership through the account's client. Only after that check succeeds does User-service call the private receiver endpoint.
5. The receiver has no authentication or ownership middleware. It is not exposed as a public service; the user-facing security boundary is User-service, and client-profile performs the account ownership check under the existing Feature 2 security decision.
6. Transaction reads use the receiver's SQLBoiler query model and filter by the resolved `account_id`.

## Consequences

### Positive

- A transaction is linked by a real CRM account UUID with a database foreign key.
- Agents receive transactions only after account ownership has been confirmed; Admins can request any account.
- Unmatched SFTP records can still be retained without being exposed as matched account transactions.
- The receiver remains focused on import and persistence, while User-service provides the public authenticated route.

### Trade-offs and risks

- Local and deployed service configuration must point to the same CRM database and keep the receiver on a private network.
- SFTP source account identifiers that are not CRM UUIDs need an explicit mapping before they can be linked.
- Existing deployments must apply the account-link migration. The current checkout has no deployment infrastructure to configure the private service path.
- The SQLBoiler transaction projection explicitly lists known columns so it can read the added database column without editing generated ORM files; regenerate ORM models if the receiver adopts the account column directly in its model.

## Alternatives considered

### Keep only the source account reference without a CRM foreign key

Rejected because arbitrary external references could appear to be valid CRM account IDs without a database-enforced link.

### Put Cognito validation and ownership authorization in the SFTP receiver

Rejected because User-service is the public security boundary for this route, and client-profile already owns the account/client ownership relation. The receiver remains private and unauthenticated.
