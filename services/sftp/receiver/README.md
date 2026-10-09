# SFTP receiver transaction API

The receiver includes an internal HTTP server in `src/serverd` for reading imported transactions. It connects to the transactions database and returns transactions for a CRM account UUID. The public transaction endpoint is provided by user-service, which authenticates the caller and checks account ownership through account-service before calling this receiver. Keep the receiver reachable only from trusted internal services.

Run the internal HTTP server from this module with the `DB_*` environment variables used by the SFTP poller. It listens on `APP_ADDR` (default `:8080`).

## Linking imported transactions to accounts

Transactions have their own database. Clients and accounts live in their services' databases, so there is no foreign key to `accounts` ([ADR-0037](../../../docs/adr/ADR-0037-transactions-database-and-account-linking.md)). Instead, the poller asks account-service while importing each file:

1. It reads and validates every row of the file.
2. It sends the distinct account references that are UUIDs to `POST {ACCOUNT_SERVICE_BASE_URL}/internal/accounts/lookup`, in batches of 500, once per file.
3. Rows whose account exists get `account_id`, `client_id` and `import_status = 'Matched'`. All others, including deleted accounts and external references such as `ACC-000123`, are stored as `Unmatched` with no `account_id`, and this API never returns them.

If account-service cannot answer, nothing from the file is saved, and the file stays in place for the next poll. Only the first failed attempt on each file sends a `SYSTEM_EXCEPTION` audit event; retries stay quiet until a lookup succeeds. `Unmatched` therefore only ever means "no such account".

The poller has no user, so it signs in with its own Cognito app client using the client-credentials grant (ADR-0025). Set these in `services/sftp/.env`; never commit the secret:

| Variable | Purpose |
|---|---|
| `ACCOUNT_SERVICE_BASE_URL` | account-service URL as the poller's container sees it (default `http://host.docker.internal:8083`) |
| `COGNITO_TOKEN_URL` | The user pool's token endpoint, `https://<domain>.auth.<region>.amazoncognito.com/oauth2/token` |
| `COGNITO_INGEST_CLIENT_ID`, `COGNITO_INGEST_CLIENT_SECRET` | The `transaction-ingest` app client |
| `COGNITO_INGEST_SCOPE` | Default `crm-accounts/lookup` |

account-service must list that app client in `COGNITO_SERVICE_CLIENT_IDS`.

### One-time Cognito setup

Someone with admin rights on the team's shared user pool does this once. It is the same pool as `COGNITO_ISSUER_URI`: `<pool-id>` is the issuer's last path segment, such as `ap-southeast-1_AbC123`. Everyone else only needs the resulting values in their own `.env`. These settings move to Terraform when infrastructure is built (ADR-0025).

1. **Add the resource server and its scope:**

   ```sh
   aws cognito-idp create-resource-server --user-pool-id <pool-id> \
     --identifier crm-accounts --name "CRM accounts" \
     --scopes ScopeName=lookup,ScopeDescription="Look up accounts for the transaction import"
   ```

2. **Add the import's app client.** It gets a secret, the client-credentials grant and only that scope:

   ```sh
   aws cognito-idp create-user-pool-client --user-pool-id <pool-id> \
     --client-name transaction-ingest --generate-secret \
     --allowed-o-auth-flows client_credentials \
     --allowed-o-auth-scopes crm-accounts/lookup \
     --allowed-o-auth-flows-user-pool-client \
     --query 'UserPoolClient.[ClientId,ClientSecret]' --output text
   ```

   The two values printed are `COGNITO_INGEST_CLIENT_ID` and `COGNITO_INGEST_CLIENT_SECRET`. To read the secret again later:

   ```sh
   aws cognito-idp describe-user-pool-client --user-pool-id <pool-id> --client-id <client-id> \
     --query 'UserPoolClient.ClientSecret' --output text
   ```

3. **Make sure the pool has a domain.** The token endpoint lives on it. The first command prints the existing prefix; if it prints `None`, create one with the second, choosing a prefix that is unique in the region:

   ```sh
   aws cognito-idp describe-user-pool --user-pool-id <pool-id> --query 'UserPool.Domain' --output text
   aws cognito-idp create-user-pool-domain --user-pool-id <pool-id> --domain <prefix>
   ```

   `COGNITO_TOKEN_URL` is then `https://<prefix>.auth.<region>.amazoncognito.com/oauth2/token`.

4. **Check that a token comes back.** This should print JSON with an `access_token`:

   ```sh
   curl -s -u "<client-id>:<client-secret>" \
     -d "grant_type=client_credentials&scope=crm-accounts/lookup" \
     "https://<prefix>.auth.<region>.amazoncognito.com/oauth2/token"
   ```

5. **Share the client ID and secret privately**, never in the repository or a chat log someone else can read. Each developer then:
   - copies `services/sftp/.env.example` to `services/sftp/.env` (gitignored) and fills in `COGNITO_TOKEN_URL`, `COGNITO_INGEST_CLIENT_ID` and `COGNITO_INGEST_CLIENT_SECRET`;
   - starts account-service with `COGNITO_SERVICE_CLIENT_IDS=<client-id>`;
   - runs `make -C services/sftp poller-once`.

   Imported rows for accounts listed in `CRM_ACCOUNT_IDS` should be `Matched`.

## Endpoint

Internal endpoint: `GET /api/accounts/{accountId}/transactions`. It has no authentication middleware and must stay private. The repository uses SQLBoiler to filter by the transaction's linked `account_id`. Results are ordered newest first. An account with no matching transactions returns `200` and an empty array.

Internal endpoint: `POST /aggregates/transactions/stats`, the business dashboard's transaction figures ([ADR-0039](../../../docs/adr/ADR-0039-dashboard-aggregate-endpoints.md)). It is also unauthenticated and private: only user-service calls it, after checking the caller and choosing the scope. The body is `{"from", "to"}` plus exactly one of `"all": true` or `"accountIds": [...]` (UUIDs, at most 20,000), so a request that forgets the account list is refused rather than read as "everything". It returns counts by status and type, a daily series, the pending backlog, and, for `all`, the unmatched count. Amounts are summed only when the window's transactions share one currency ([ADR-0038](../../../docs/adr/ADR-0038-dashboard-metric-definitions.md)).

The receiver keeps the file's account reference in `source_account_ref`. For local demo data, create an account through account-service, then set `CRM_ACCOUNT_IDS` to its returned `account_id` before starting the producer. Multiple UUIDs can be comma-separated.
