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

## Connection resilience

[ADR-0024](../../../docs/adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 3 protects the SFTP connection with timeouts, retries and a circuit breaker:

- **Retries:** each poll tries to connect and list `SFTP_REMOTE_DIR` up to 3 times. Before the second attempt it waits 2 seconds, before the third 4 seconds, each plus up to 1 second at random. It does not retry a private key it cannot read, a host key that does not match `SFTP_HOST_KEY_FINGERPRINT`, or a refused login: retrying cannot fix them. Nor does it wait so long that less than a minute of a poll with a deadline would be left. Only the last attempt's error is audited, as one `CONNECTION_FAILURE` per outage.
- **Circuit breaker:** after 3 polls in a row cannot connect, the circuit opens for 15 minutes. While it is open, a poll does not connect: it logs `SFTP circuit open until <time>`, still relays audit events, and audits nothing new. After the 15 minutes, one poll tries again; if it connects, the circuit closes, and if not, it opens for another 15 minutes. The state is kept in memory, like failure episodes, so a restarted poller starts with the circuit closed.
- **Timeouts:** connecting and the SSH handshake have 10 seconds each. A whole poll has at most 10 minutes, or less if its context ends sooner, as a Lambda invocation's does. When the poll's time is up, the poller closes the connection, which ends any SFTP call still waiting for the server.

## Audit events

When `AUDIT_QUEUE_URL` is set (with `AWS_REGION`), the poller records a `TRANSACTION_INGESTION` audit event for each processed file (`FILE_PROCESSING_COMPLETED`) and for each failure (`CONNECTION_FAILURE`, `FILE_FORMAT_INVALID` or `SYSTEM_EXCEPTION`). This receiver acts as transaction-service and transaction-ingest, so it implements [ADR-0020](../../../docs/adr/ADR-0020-audit-write-path-outbox-shared-sqs.md) decision 3 through the `audit_outbox` table:

- A file's transactions and its `FILE_PROCESSING_COMPLETED` event are saved in one database transaction: an imported file always has its event, and a failed import saves neither and leaves the file for the next poll.
- A failure event has no import to join, so it is saved on its own. If it cannot be saved, typically because the database is down, it is sent straight to SQS so the failure is still recorded.
- A failure that repeats on every poll is audited once per episode, with the time the episode began as `occurred_at` ([ADR-0024](../../../docs/adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 5). The episode ends when the failing step succeeds: a connection failure when a poll connects and lists the directory, an account lookup failure when a lookup succeeds, and any other failure on a file when the file is imported or leaves the directory. A later episode of the same failure has a new `occurred_at`, and so a new event ID, so the Auditor stores it rather than dropping it as a repeat. If a failure event can be neither saved nor sent, the next poll tries again with the same `occurred_at`. Episodes are kept in memory, so restarting the poller audits an ongoing failure again.
- At the end of every poll, including one whose connection failed, the poller sends every waiting event to SQS, oldest first, and deletes each once SQS accepts it. If SQS cannot be reached, the events wait for the next poll. Delivery is at least once; event IDs are derived from the event's content, so the Auditor drops repeats.

The outbox is written with plain SQL, so `sqlboiler.toml` leaves `audit_outbox` out of the generated ORM. A database volume created before the outbox has no `audit_outbox` table, so with auditing on every import fails until you recreate the volume with `docker compose -f services/sftp/compose.yaml down -v`.

## Endpoint

Internal endpoint: `GET /api/accounts/{accountId}/transactions`. It has no authentication middleware and must stay private. The repository uses SQLBoiler to filter by the transaction's linked `account_id`. Results are ordered newest first. An account with no matching transactions returns `200` and an empty array.

Internal endpoint: `GET /api/transactions?cursor&size&status`, every account's transactions for the admin list ([ADR-0043](../../../docs/adr/ADR-0043-client-and-transaction-list-endpoints.md)). It is unauthenticated and private like the route above: user-service lets only admins through. It returns `{content, nextCursor}`, newest first by `transaction_date` then `transaction_id`, both descending. `size` is 1 to 100 (default 20); `status` is optionally `Completed`, `Pending` or `Failed`; pass `nextCursor` back as `cursor` for the next page, and it is `null` on the last. Rows not linked to an account are left out, as above. Paging is by keyset, so later pages stay as fast as the first.

Internal endpoint: `POST /aggregates/transactions/stats`, the business dashboard's transaction figures ([ADR-0039](../../../docs/adr/ADR-0039-dashboard-aggregate-endpoints.md)). It is also unauthenticated and private: only user-service calls it, after checking the caller and choosing the scope. The body is `{"from", "to"}` plus exactly one of `"all": true` or `"accountIds": [...]` (UUIDs, at most 20,000), so a request that forgets the account list is refused rather than read as "everything". It returns counts by status and type, a daily series, the pending backlog, and, for `all`, the unmatched count. Amounts are summed only when the window's transactions share one currency ([ADR-0038](../../../docs/adr/ADR-0038-dashboard-metric-definitions.md)).

The receiver keeps the file's account reference in `source_account_ref`. For local demo data, create an account through account-service, then set `CRM_ACCOUNT_IDS` to its returned `account_id` before starting the producer. Multiple UUIDs can be comma-separated.

## Running as a Lambda function

[ADR-0024](../../../docs/adr/ADR-0024-transaction-ingestion-sftp-lambda.md) runs the import as a scheduled Lambda function. It is the same `src/cronjob` binary, built as `bootstrap` for `provided.al2023` on arm64:

```sh
cd services/sftp
make lambda   # writes receiver/dist/transaction-ingest.zip: bootstrap and rds-ca.pem
```

The binary runs as a Lambda function when Lambda's `AWS_LAMBDA_RUNTIME_API` is set:

- **One invocation is one poll**, as `-once` is locally. The poll has the invocation's deadline, so it starts no new file once less than a minute remains. The files left wait for the next poll, and the poll's audit events are still relayed. A failed poll fails the invocation, which the `transaction-ingest-errors` alarm counts.
- **State:** the poller is built once, at init, and reused on every invocation, so the circuit breaker and failure episodes carry across warm invocations. A new execution environment starts with them empty, as ADR-0024 accepts.
- **Secrets** are read from Secrets Manager at init, never set as Lambda environment variables. Each variable below names a secret; when it is unset, the value comes from the local variables instead:

  | Variable | Secret | Replaces |
  |---|---|---|
  | `DB_SECRET_ARN` | transactions-db's RDS-managed login, JSON with `username` and `password` | `DB_USER`, `DB_PASSWORD` |
  | `SFTP_PRIVATE_KEY_SECRET` | The OpenSSH private key | `SFTP_PRIVATE_KEY_PATH` |
  | `COGNITO_INGEST_SECRET` | JSON with `client_id` and `client_secret` | `COGNITO_INGEST_CLIENT_ID`, `COGNITO_INGEST_CLIENT_SECRET` |

  `SFTP_HOST_KEY_FINGERPRINT` is not secret and stays an environment variable.
- **Database:** `DB_SSLMODE=verify-full`, with `DB_SSLROOTCERT=/var/task/rds-ca.pem`, the RDS CA bundle in the zip.
- **Retries:** Lambda does not retry a failed invocation, and drops one throttled for more than 5 minutes behind a long run. The next scheduled run picks the files up.

The function, its schedule, network and IAM are in `infrastructure/terraform/stacks/crm/lambda.tf` and `scheduler.tf`, behind `enable_ingest_lambda`. [The deployment runbook](../../../docs/runbooks/deploy-aws.md#8-the-external-sftp-server-and-the-ingest-lambda) turns it on.
