# ADR-0037: Transactions in their own database, linked to accounts through account-service at import

- Status: Accepted
- Decision date/time: 2026-10-08 21:45 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen; Joseph Yau (Feature 3 owner and owner of ADR-0006, who agreed on 2026-10-08 to replacing the receiver's account link with an API call)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/transaction-integration/separate-transactions-db`
- Related issue/PR: TBD (pull request from `task/transaction-integration/separate-transactions-db` to `dev`)
- Supersedes: ADR-0006, decisions 1 (one shared CRM database) and 2 (foreign key and linking trigger) only; decisions 3, 5 and 6 remain in force, and decision 4 was superseded by ADR-0035
- Superseded by: N/A

## Context

[ADR-0017](ADR-0017-microservices-per-business-area.md) gives each service its own database. Since [ADR-0035](ADR-0035-account-ownership-across-services.md), client-service and account-service work that way. Transactions did not: locally, clients, accounts and transactions all shared the SFTP stack's `crm` database. The reason was [ADR-0006](ADR-0006-transaction-account-linkage-and-access.md) decisions 1–2. When the receiver inserts an imported row, a trigger looks `source_account_ref` up in `accounts`, sets `transactions.account_id` and `import_status` (`Matched` or `Unmatched`), and a foreign key keeps `account_id` pointing at a real account. Neither a trigger nor a foreign key can reach another database.

The SFTP stack is a fake-data harness. Its producer stands in for the core banking system and writes transactions for the account IDs in `CRM_ACCOUNT_IDS`. The producer reads no database, but its transactions are only useful if they link to real CRM accounts, which the trigger did.

## Decision Drivers

- One database per service, locally as deployed (ADR-0017).
- Keep `Matched`/`Unmatched` meaningful: `Unmatched` means "no such account".
- Keep the read path and its agent scoping unchanged.
- No call is trusted because of where it comes from ([security model](../security/security-model.md), [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md)).
- An account-service outage must not mislabel imported rows.
- The producer's fake data must keep linking.

## Options Considered

### Option A — Drop the import-time link; rely only on the read-time check

- Pros: no new endpoint, no service credentials; imports never depend on account-service. The read path already hides other agents' and deleted accounts.
- Cons: `Matched`/`Unmatched` and `client_id` lose their meaning; `account_id` would only mean "looks like a UUID".

### Option B — Look accounts up through account-service at import (chosen)

- Pros: the link stays accurate; `client_id` is filled; the receiver no longer needs the account schema.
- Cons: a new internal endpoint and a service credential; an import waits while account-service is down.

### Option C — Keep one shared database locally

- Pros: no work.
- Cons: the local layout differs from the deployed one, where the trigger and foreign key cannot exist; the cross-service dependency stays hidden.

For the import's credential, a private unauthenticated endpoint was also considered and rejected. It contradicts the security model's "no network trust", which ADR-0025 decision 4 meets with Cognito client-credentials tokens.

## Decision

**Option B.**

1. **Transactions have their own database** (`transactions`). The receiver's foreign key and trigger are removed. `transactions.account_id` is a plain nullable UUID column, with no foreign key.
2. **account-service serves `POST /internal/accounts/lookup`.**
   - Body `{"ids": [...]}`, with at most 500 IDs; IDs that are not UUIDs are ignored.
   - It returns `{"accounts": [{"id", "client_id"}]}` for the accounts that exist and are not deleted ([ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md)).
   - It sits outside `/api`, so API Gateway never routes it. It is not audited: a lookup changes nothing, and the import audits each file.
3. **The import authenticates with a Cognito client-credentials token** (ADR-0025 decision 4).
   - The shared user pool has a resource server `crm-accounts` with scope `lookup`, and an app client `transaction-ingest` with a secret, allowed only that scope.
   - On `/internal`, account-service accepts only tokens whose app client is listed in `COGNITO_SERVICE_CLIENT_IDS`, that carry the scope, and that have no user groups.
   - User tokens are refused on `/internal`, and service tokens on `/api`.
4. **Linking happens once per file.** After validating every row, the poller sends the file's distinct UUID references, case-insensitively, in batches of 500.
   - Rows whose account is returned get `account_id`, `client_id` and `Matched`.
   - All others are `Unmatched`: unknown accounts, deleted accounts and external references.
5. **Fail closed.** If account-service gives no answer, nothing from the file is saved, and the file stays for the next poll.
   - A `SYSTEM_EXCEPTION` audit event is sent for the first failed attempt on each file.
   - Retries of that file are not audited again until a lookup succeeds, so an outage does not add an event to the chain on every poll. A poller restart audits the next failure again.
6. **No later re-linking.** A row imported before its account existed stays `Unmatched`, as it did with the trigger. A reconciliation job can be added if needed.
7. **The read path is unchanged.** user-service checks the account with account-service, then reads the receiver by `account_id` (ADR-0006 decisions 3, 5 and 6; ADR-0035 decision 6).
8. **Locally, each service has its own database:**
   - clients on port 5432;
   - accounts on 5436, moved from 5434, which user-service's local database also uses;
   - transactions on 5437.

   The `run-shared-db` and `db-attach-crm` targets are removed. The producer is unchanged.

## Rationale

The link is the only thing tying transactions to the account schema, and account-service already owns the answer to "does this account exist". Asking it once per file keeps the import's meaning intact at the cost of one request. Using a client-credentials token gives the import a verifiable identity with exactly one permission, as ADR-0025 proposes for every caller without a user. Failing closed keeps a data label from depending on whether another service happened to be up.

## Consequences

### Positive

- Clients, accounts and transactions are separate locally, as deployed.
- The receiver's schema no longer mentions accounts.
- `client_id` is now filled on matched transactions.
- This is the first use of ADR-0025's service tokens. The health monitor's daily task can use the same mechanism.

### Negative / Trade-offs

- Imports pause while account-service or Cognito is down. Files wait on the SFTP server and are retried.
- Nothing in the database guarantees that `account_id` exists; the guarantee is the lookup at import time.
- account-service's copy of the Cognito verifier now differs from client-service's.
- Setup needs a Cognito resource server, an app client with a secret, and a user pool domain for the token endpoint.
- Locally, the poller sends its token to account-service over plain HTTP via `host.docker.internal`. Deployed, the call must use HTTPS.

## Security & Privacy Impact

- **Least privilege.** The import's token carries one scope that only allows looking accounts up. The lookup reveals only whether an account exists and its client ID.
- **Secrets.** The client secret lives in the gitignored `services/sftp/.env` locally, and in Secrets Manager when deployed. It is never committed.
- **No network trust.** `/internal` routes require a valid service token even inside the network, and API Gateway does not expose them.

## Cost Impact

Cognito charges per successful machine-to-machine token request, about USD 0.00225 each, with no charge per app client since November 2025 ([Cognito pricing](https://aws.amazon.com/cognito/pricing/)). The poller reuses each token until a minute before it expires (60 minutes by default), so about 720 requests a month if it ran continuously: under USD 2. Locally it runs only while developers use it.

## Operational Impact

- account-service needs `COGNITO_SERVICE_CLIENT_IDS`.
- The poller needs `ACCOUNT_SERVICE_BASE_URL`, `COGNITO_TOKEN_URL`, `COGNITO_INGEST_CLIENT_ID`, `COGNITO_INGEST_CLIENT_SECRET` and optionally `COGNITO_INGEST_SCOPE`.
- **Infrastructure requirements** for later:
  - the resource server, app client and scope in Terraform (ADR-0025);
  - the secret in Secrets Manager;
  - a private route from the ingest worker to account-service's `/internal`. The Lambda in ADR-0024 cannot use ECS Service Connect, so it needs the internal load balancer or another VPC route.
- Existing local SFTP volumes hold the old `crm` database. Remove them once with `docker compose -f services/sftp/compose.yaml down -v`.

## Validation

- **account-service:**
  - signed-token tests for both middlewares: a service token is admitted only with the right app client and scope; user tokens are refused on `/internal`; service tokens are refused on `/api`;
  - a repository test that the lookup skips deleted and unknown accounts;
  - service and controller tests for the 500-ID limit, non-UUID IDs and errors.
- **Receiver:**
  - poller tests: one lookup per file, case-insensitive matching, unmatched and external references, a lookup failure that saves nothing and keeps the file, and an outage audited once per file rather than on every retry;
  - `httptest` tests for the token and lookup client (token reuse, batching, every failure mode);
  - a repository test that the link columns are stored.
- **End to end (not yet run):**
  - a producer file for a real account is `Matched`, and its transactions are readable through user-service by the owner only;
  - after the account is deleted, new rows are `Unmatched`;
  - with account-service stopped, the file waits and is imported on a later poll.

## References

- [ADR-0006](ADR-0006-transaction-account-linkage-and-access.md) (decisions 1–2 superseded), [ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md), [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md), [ADR-0035](ADR-0035-account-ownership-across-services.md), [ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md)
- [Open item 6](../architecture/open-items.md#6-splitting-accounts-out-of-client-profile-management-service), [open item 9](../architecture/open-items.md#9-client-id-validation-at-import)
- `services/sftp/receiver/README.md`, `services/account-service/README.md`
