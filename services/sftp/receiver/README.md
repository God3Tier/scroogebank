# SFTP receiver transaction API

The receiver includes an internal HTTP server in `src/serverd` for reading imported transactions. It connects to the transactions database and returns transactions for a CRM account UUID. The public transaction endpoint is provided by user-service, which authenticates the caller and checks account ownership through account-service before calling this receiver. Keep the receiver reachable only from trusted internal services.

Run the internal HTTP server from this module with the `DB_*` environment variables used by the SFTP poller. It listens on `APP_ADDR` (default `:8080`).

Both the poller and the HTTP server read `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `DB_NAME`, `DB_SSLMODE` and `DB_SSLROOTCERT`. `DB_SSLMODE` defaults to `disable`, which is for the local database only; against RDS, set `DB_SSLMODE=verify-full` and `DB_SSLROOTCERT` to the RDS CA bundle.

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

## Re-sent transactions

A transaction already imported keeps its first version. A later file with the same `transaction_id` changes only a `Pending` status, to `Completed` or `Failed`: it never reopens a settled transaction, and never changes the amount, date, description or account link ([ADR-0024](../../../docs/adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 4). A row imported as `Unmatched` therefore stays `Unmatched` ([ADR-0037](../../../docs/adr/ADR-0037-transactions-database-and-account-linking.md) decision 6).

## Rejected files

The poller takes every regular file in `SFTP_REMOTE_DIR` except `.tmp` files, which are still being uploaded ([ADR-0024](../../../docs/adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 4). It rejects a whole file when it cannot read it:

- a file with any extension other than `.json` is unsupported;
- a `.json` file that is not a JSON array of transactions, which is how a truncated or damaged file shows.

It records a `FILE_FORMAT_INVALID` event (stage `FILE_FORMAT`) and then moves the file to `rejected/`. The file moves only once its event is saved or sent: if neither works, the file stays and the next poll tries again. A file whose rows are partly or all invalid is different: it is imported, its `FILE_PROCESSING_COMPLETED` event lists the rejected rows, and it also moves to `rejected/`.

A file keeps its name when it moves to `processed/` or `rejected/`. If a file of that name is already there, OpenSSH refuses the move, so the file stays, with one `SYSTEM_EXCEPTION` event, until the older file is removed. Generated `report-<time>-<id>.json` names make this unlikely.

## Audit events

When `AUDIT_QUEUE_URL` is set (with `AWS_REGION`), the poller records a `TRANSACTION_INGESTION` audit event for each processed file (`FILE_PROCESSING_COMPLETED`) and for each failure (`CONNECTION_FAILURE`, `FILE_FORMAT_INVALID` or `SYSTEM_EXCEPTION`). This receiver acts as transaction-service and transaction-ingest, so it implements [ADR-0020](../../../docs/adr/ADR-0020-audit-write-path-outbox-shared-sqs.md) decision 3 through the `audit_outbox` table:

- A file's transactions and its `FILE_PROCESSING_COMPLETED` event are saved in one database transaction: an imported file always has its event, and a failed import saves neither and leaves the file for the next poll.
- A failure event has no import to join, so it is saved on its own. If it cannot be saved, typically because the database is down, it is sent straight to SQS so the failure is still recorded.
- A failure that repeats on every poll is audited once per episode, with the time the episode began as `occurred_at` ([ADR-0024](../../../docs/adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 5). The episode ends when the failing step succeeds: a connection failure when a poll connects and lists the directory, an account lookup failure when a lookup succeeds, and any other failure on a file when the file is imported or leaves the directory. A later episode of the same failure has a new `occurred_at`, and so a new event ID, so the Auditor stores it rather than dropping it as a repeat. If a failure event can be neither saved nor sent, the next poll tries again with the same `occurred_at`. Episodes are kept in memory, so restarting the poller audits an ongoing failure again.
- At the end of every poll, including one whose connection failed, the poller sends every waiting event to SQS, oldest first, and deletes each once SQS accepts it. If SQS cannot be reached, the events wait for the next poll. Delivery is at least once; event IDs are derived from the event's content, so the Auditor drops repeats.

The outbox is written with plain SQL, so `sqlboiler.toml` leaves `audit_outbox` out of the generated ORM. A database volume created before the outbox has no `audit_outbox` table, so with auditing on every import fails until you recreate the volume with `docker compose -f services/sftp/compose.yaml down -v`.

## Endpoint

Internal endpoint: `GET /api/accounts/{accountId}/transactions`. It has no authentication middleware and must stay private. The repository uses SQLBoiler to filter by the transaction's linked `account_id`. Results are ordered newest first. An account with no matching transactions returns `200` and an empty array.

Internal endpoint: `POST /aggregates/transactions/stats`, the business dashboard's transaction figures ([ADR-0039](../../../docs/adr/ADR-0039-dashboard-aggregate-endpoints.md)). It is also unauthenticated and private: only user-service calls it, after checking the caller and choosing the scope. The body is `{"from", "to"}` plus exactly one of `"all": true` or `"accountIds": [...]` (UUIDs, at most 20,000), so a request that forgets the account list is refused rather than read as "everything". It returns counts by status and type, a daily series, the pending backlog, and, for `all`, the unmatched count. Amounts are summed only when the window's transactions share one currency ([ADR-0038](../../../docs/adr/ADR-0038-dashboard-metric-definitions.md)).

The receiver keeps the file's account reference in `source_account_ref`. For local demo data, create an account through account-service, then set `CRM_ACCOUNT_IDS` to its returned `account_id` before starting the producer. Multiple UUIDs can be comma-separated.

## Running as a Lambda function

[ADR-0024](../../../docs/adr/ADR-0024-transaction-ingestion-sftp-lambda.md) runs the import as a scheduled Lambda function. The handler is not built yet; the poller already does what one invocation needs:

- `-once` polls once and exits, as each invocation will.
- A poll with a deadline, such as an invocation's context, starts no new file once less than a minute remains. The files left wait for the next poll, and the poll's audit events are still relayed.

Requirements for when the function and its infrastructure are built:

- **Runtime:** the compiled Go binary on `provided.al2023`, attached to the VPC in the private app subnets of both AZs, invoked by EventBridge Scheduler about every 5 minutes, with reserved concurrency 1 and a timeout well above the one-minute reserve.
- **State:** build the poller once, at init, and reuse it on every invocation, so failure episodes carry across warm invocations.
- **Secrets:** the SSH private key and `COGNITO_INGEST_CLIENT_SECRET` in Secrets Manager, read at init and never set as Lambda environment variables; the SFTP host key fingerprint in SSM Parameter Store. The poller reads them from `SFTP_PRIVATE_KEY_PATH` and environment variables today, so the handler has to load them.
- **Database:** `DB_SSLMODE=verify-full`, with `DB_SSLROOTCERT` pointing to the RDS CA bundle packaged with the function.
- **Network:** outbound 22 through the NAT gateway to the SFTP server; outbound 443 to Cognito and SQS (through the NAT gateway or VPC endpoints) and to the internal ALB; 5432 to transactions-db. The internal ALB accepts the function's security group ([ADR-0019](../../../docs/adr/ADR-0019-edge-entry-chain.md) decision 4) and routes `/internal/accounts/*` to account-service, and `ACCOUNT_SERVICE_BASE_URL` is the ALB's HTTPS address.
- **IAM:** `sqs:SendMessage` on `audit-insert` (and its KMS key, if it has one), `secretsmanager:GetSecretValue` on the two secrets, `ssm:GetParameter` on the fingerprint, and the permissions Lambda needs to create its VPC network interfaces.
