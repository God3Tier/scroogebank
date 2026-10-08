# Account Service (Feature 2)

Owns bank accounts. Split out of `client-profile-management-service`, which still owns clients, so each has its own database ([ADR-0017](../../docs/adr/ADR-0017-microservices-per-business-area.md), [ADR-0035](../../docs/adr/ADR-0035-account-ownership-across-services.md)). Written in Go because it was extracted from that service's existing account code.

## Endpoints

| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/accounts` | ADMIN/AGENT | Open an account for a client the caller may access |
| GET | `/api/accounts?clientId={uuid}` | ADMIN/AGENT | List a client's accounts. Admins see all; agents see only accounts they own |
| GET | `/api/accounts/{accountId}` | ADMIN/AGENT | Read an account. Agents: their own only |
| DELETE | `/api/accounts/{accountId}` | ADMIN/AGENT | Delete an account. Agents: their own only |

`GET /health` is public; all `/api` routes require a Cognito **access token**.

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

client-profile-management-service calls the list endpoint before deleting a client, and refuses the delete while accounts remain.

## Start locally

```sh
make run COGNITO_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id> COGNITO_APP_CLIENT_ID=<app-client-id> AWS_REGION=<region> AUDIT_QUEUE_URL=<audit-insert-queue-url>
```

This starts this service's own PostgreSQL on port 5434, loads `data/V1_init.sql`, and runs the API at `http://localhost:8083`. Account creation needs client-profile-management-service running at `CLIENT_SERVICE_BASE_URL` (default `http://localhost:8080`).

For the SFTP transaction integration, start the shared database with `make -C ../sftp up`, then run `make run-shared-db` here and in `../client-profile-management-service`. Locally, clients, accounts and transactions then share the SFTP stack's `crm` database, because the receiver's `transactions.account_id` foreign key needs `accounts` in the same database. Deployed, each service has its own database; replacing that foreign key is Feature 3 follow-up work.

| Command | Purpose |
|---|---|
| `make test` | Disposable PostgreSQL (port 5435), SQLBoiler generation, then `go test ./... -cover` |
| `make mockery` | Regenerate mocks. Mocks and the SQLBoiler models are generated, not committed |
| `make sqlboiler` | Regenerate the SQLBoiler models from `data/V1_init.sql` |
| `make down` / `make db-reset` | Stop the database, keeping or deleting its data |

## Configuration

| Env var | Purpose |
|---|---|
| `CLIENT_SERVICE_BASE_URL` | client-profile-management-service URL. Required; must be `https://` in production, because the caller's token is forwarded to it |
| `COGNITO_ISSUER_URI`, `COGNITO_APP_CLIENT_ID`, `COGNITO_API_AUDIENCE` | Token validation, as in client-profile-management-service |
| `AWS_REGION`, `AUDIT_QUEUE_URL`, `AWS_ENDPOINT_URL` | Auditor SQS queue |
| `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `DB_NAME`, `DB_SSLMODE`, `DB_SSLROOTCERT` | This service's own database (defaults `localhost:5434`, `accounts`) |
| `APP_ENV`, `APP_ADDR`, `APP_TLS_CERT_FILE`, `APP_TLS_KEY_FILE` | `local` or `production`; listen address (default `:8083`); TLS in production |

## Auditor SQS events

The service sends one SQS message per completed account endpoint call, with `source_service: CRM`, the same as client events, so the Auditor needs no change. Each event records the event type (`CRUD`), normalized action, authenticated actor, route, HTTP method, status code, timestamp, and the client or account ID. It does not copy request or response bodies.

## Production transport security

Set `APP_ENV=production` with `APP_TLS_CERT_FILE`, `APP_TLS_KEY_FILE`, `DB_SSLMODE=verify-full` and `DB_SSLROOTCERT`. The service defaults to production security and refuses to start in production mode if these settings, or an `https` `CLIENT_SERVICE_BASE_URL`, are missing.
