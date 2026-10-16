# Account Service (Feature 2)

Owns bank accounts. Split out of `client-profile-management-service`, which still owns clients, so each has its own database ([ADR-0017](../../docs/adr/ADR-0017-microservices-per-business-area.md), [ADR-0035](../../docs/adr/ADR-0035-account-ownership-across-services.md)). Written in Go because it was extracted from that service's existing account code.

## Endpoints

| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/accounts` | ADMIN/AGENT | Open an account for a client the caller may access |
| GET | `/api/accounts?clientId={uuid}` | ADMIN/AGENT | List a client's accounts that are not deleted. Admins see all; agents see only accounts they own |
| GET | `/api/accounts/{accountId}` | ADMIN/AGENT | Read an account. Agents: their own only |
| DELETE | `/api/accounts/{accountId}` | ADMIN/AGENT | Soft-delete an account. Agents: their own only |
| POST | `/internal/accounts/lookup` | Services only | Which of up to 500 account IDs exist (not deleted), with their client IDs. Used by the transaction import |
| GET | `/aggregates/accounts/stats?from&to[&groupBy=agent]` | ADMIN/AGENT | The business dashboard's account figures. Agents: their own accounts; per-agent rows for admins only |
| GET | `/aggregates/accounts/ids` | ADMIN/AGENT | The IDs of the accounts the caller owns. user-service scopes an agent's transaction figures by them |

`GET /health` is public; all `/api` routes require a Cognito user **access token**.

`/internal` routes take only Cognito **client-credentials** tokens: the token's app client must be listed in `COGNITO_SERVICE_CLIENT_IDS`, and it must carry the scope `crm-accounts/lookup`. User tokens are refused there, and service tokens are refused on `/api`. The routes sit outside `/api`, so API Gateway never routes them ([ADR-0037](../../docs/adr/ADR-0037-transactions-database-and-account-linking.md)). Lookups are not audited: they change nothing, and the import audits each file.

Request `{"ids": ["<uuid>", ...]}`; response `{"accounts": [{"id": "<uuid>", "client_id": "<uuid>"}]}`. IDs that are not UUIDs are ignored.

`/aggregates` routes take the caller's user access token, forwarded by the dashboard or user-service, and scope every figure by it. They sit outside `/api` so API Gateway never routes them, and are not audited here: the dashboard audits each view ([ADR-0039](../../docs/adr/ADR-0039-dashboard-aggregate-endpoints.md)). Deleted accounts are never counted, and deposits are summed only when the window's accounts share one currency, otherwise 409 ([ADR-0038](../../docs/adr/ADR-0038-dashboard-metric-definitions.md)). Contract: [`docs/api/dashboard-aggregates.yaml`](../../docs/api/dashboard-aggregates.yaml).

## Deleting an account

`DELETE /api/accounts/{accountId}` is a **soft delete** ([ADR-0036](../../docs/adr/ADR-0036-soft-deletion-of-clients-and-accounts.md)), as the requirements Q&A asks: it sets `deleted_at`, keeps the row, and leaves `status` as it was.

**Deleted is not the same as closed.** `status = 'closed'` is a business state of an account that still exists: it is still listed and readable. A deleted account is treated as missing by every endpoint, for admins and agents alike: reads and a second delete return 404, and lists leave it out. Its transactions also return 404 through user-service, whose ownership check reads the account. Nothing restores a deleted account. A deleted account's type can be opened again for the same client.

Because the row is kept, imported transactions that refer to it are never orphaned.

Deleted rows are filtered by SQLBoiler's `add-soft-deletes` setting in `sqlboiler.toml` and `sqlboiler.docker.toml`, which makes every generated query skip them. All account queries, including `RequireAccountOwner`, go through the generated code; any hand-written SQL added later must add `deleted_at IS NULL` itself. Account responses include `deleted_at`, which is always `null` for an account the API returns.

## Ownership across services

The `accounts` table has no foreign key to `clients`, because clients live in another service's database. Two things replace it:

- **Creating an account** calls `GET {CLIENT_SERVICE_BASE_URL}/api/clients/{client_id}` with the caller's own token. client-profile-management-service applies its ownership rule: admins see every client, and an agent asking about another agent's client gets 404.

  | client service answers | Response |
  |---|---|
  | 200 | The account is created. The client's `created_by_agent_id` is stored as `owner_agent_id` |
  | 404 | 404, and nothing is created |
  | Anything else, or no answer within 2 seconds | 503, and nothing is created |

  Admins go through this check too: it is what proves the client exists. The owner is the client's owner, not the caller, so an admin opening an account does not become its owner.
- **Reading or deleting an account** compares the caller's `sub` with the stored `owner_agent_id`. That is a local query, with no call to another service. Admins skip it; an agent who does not own the account gets 404, the same as for a missing one.

Copying the owner is correct because a client's owner never changes: nothing updates `created_by_agent_id`, and there is no reassignment feature. If one is added, the copies must be updated with it (ADR-0035).

client-profile-management-service calls the list endpoint before deleting a client, and refuses the delete while accounts remain. Deleted accounts are not listed, so they do not block it; closed accounts that are not deleted do.

## Start locally

```sh
make run COGNITO_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id> COGNITO_APP_CLIENT_ID=<app-client-id> AWS_REGION=<region> AUDIT_QUEUE_URL=<audit-insert-queue-url>
```

This starts this service's own PostgreSQL on port 5436 (5434 is user-service's), loads `data/V1_init.sql`, and runs the API at `http://localhost:8083`. A database volume created before soft delete has no `accounts.deleted_at` column, and one created before the audit outbox has no `audit_outbox` table, so no audit events are recorded (each attempt is logged); run `make db-reset` to recreate it. Account creation needs client-profile-management-service running at `CLIENT_SERVICE_BASE_URL` (default `http://localhost:8080`).

For the SFTP transaction integration, add `COGNITO_SERVICE_CLIENT_IDS=<transaction-ingest app client ID>` to the command above, run client-profile-management-service with its own `make run`, then start the receiver with `make -C ../sftp up`. Each service keeps its own database; the import links transactions to accounts through `/internal/accounts/lookup`.

| Command | Purpose |
|---|---|
| `make test` | Disposable PostgreSQL (port 5435), SQLBoiler models and mocks, then `go test ./... -cover`. Works from a clean checkout; CI runs it |
| `make mockery` | Regenerate mocks. Mocks and the SQLBoiler models are generated, not committed |
| `make sqlboiler` | Regenerate the SQLBoiler models from `data/V1_init.sql` |
| `make down` / `make db-reset` | Stop the database, keeping or deleting its data |

## Configuration

| Env var | Purpose |
|---|---|
| `CLIENT_SERVICE_BASE_URL` | client-profile-management-service URL. Required; must be `https://` in production, because the caller's token is forwarded to it |
| `COGNITO_ISSUER_URI`, `COGNITO_APP_CLIENT_ID`, `COGNITO_API_AUDIENCE` | Token validation, as in client-profile-management-service |
| `COGNITO_SERVICE_CLIENT_IDS` | Comma-separated Cognito app clients allowed to call `/internal` with a client-credentials token (the transaction import's). Empty: every `/internal` call is refused |
| `AWS_REGION`, `AUDIT_QUEUE_URL`, `AWS_ENDPOINT_URL` | Auditor SQS queue |
| `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `DB_NAME`, `DB_SSLMODE`, `DB_SSLROOTCERT` | This service's own database (defaults `localhost:5436`, `accounts`) |
| `APP_ENV`, `APP_ADDR`, `APP_TLS_CERT_FILE`, `APP_TLS_KEY_FILE` | `local` or `production`; listen address (default `:8083`); TLS in production |

## Auditor SQS events

The service records one audit event per completed account endpoint call, with `source_service: CRM`, the same as client events, so the Auditor needs no change. Each event records the event type (`CRUD`), normalized action, authenticated actor, route, HTTP method, status code, timestamp, and the client or account ID. It does not copy request or response bodies.

**Delivery** ([ADR-0020](../../docs/adr/ADR-0020-audit-write-path-outbox-shared-sqs.md)). The audit middleware saves each event to the `audit_outbox` table in a transaction of its own, after the endpoint's change has committed. This is ADR-0020's exception for producers that build their events in request middleware, used because of the cost of moving event creation into the service layer. Every 2 seconds a relay goroutine (`src/serverd/sqs/outbox.go`) takes the oldest waiting events (up to 50, locked with `FOR UPDATE SKIP LOCKED` so replicas take different rows), sends each to SQS, and deletes it once SQS accepts it. An SQS or Auditor outage therefore delays events instead of losing them. Delivery is at least once: a task that dies between the send and the delete sends the event again, and the Auditor drops the repeat by `event_id`. Two gaps remain: a crash between the endpoint's commit and the outbox write loses that event, and an outbox write that fails is logged with its event ID and dropped. The outbox table is written with plain SQL, so `sqlboiler.toml` and `sqlboiler.docker.toml` leave it out of the generated ORM.

## Production transport security

Set `APP_ENV=production` with `APP_TLS_CERT_FILE`, `APP_TLS_KEY_FILE`, `DB_SSLMODE=verify-full` and `DB_SSLROOTCERT`. The service defaults to production security and refuses to start in production mode if these settings, or an `https` `CLIENT_SERVICE_BASE_URL`, are missing.
