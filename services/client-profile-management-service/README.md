# Client Profile Management Service (Feature 2)

Owns client profiles. Accounts moved to [`account-service`](../account-service/README.md), which has its own database ([ADR-0017](../../docs/adr/ADR-0017-microservices-per-business-area.md), [ADR-0035](../../docs/adr/ADR-0035-account-ownership-across-services.md)).

## Start locally

The local service still verifies Cognito access tokens and records a CRM audit event for the Auditor's SQS queue after each protected endpoint completes. Provide the Cognito settings and Auditor queue configuration when starting it:

```sh
make run COGNITO_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id> COGNITO_APP_CLIENT_ID=<app-client-id> AWS_REGION=<region> AUDIT_QUEUE_URL=<audit-insert-queue-url>
```

The command starts PostgreSQL, initializes or migrates its schema, and runs the API at `http://localhost:8080`. `GET /health` is public; all `/api` routes require a Cognito **access token**. Set `Authorization: Bearer <access-token>` in Bruno or your API client.

Deleting a client asks account-service first, at `ACCOUNT_SERVICE_BASE_URL` (default `http://localhost:8083`). Run account-service alongside this service, or client deletion returns 503.

Each service has its own database, including locally: this one on port 5432, account-service's on 5436 and the SFTP stack's transactions database on 5437 ([ADR-0037](../../docs/adr/ADR-0037-transactions-database-and-account-linking.md)). For the SFTP transaction integration, run `make run` here and in `../account-service`, then start the receiver with `make -C ../sftp up`.

To stop PostgreSQL and other Compose dependencies while keeping the data volume, run `make down`. To delete the local database data, run `make db-reset`. A database volume created before the account split still has an `accounts` table here; it is unused, and `make db-reset` removes it. A volume created before soft delete has no `clients.deleted_at` column, so every request fails until you run `make db-reset`. A volume created before the audit outbox has no `audit_outbox` table, so no audit events are recorded (each attempt is logged) until you run `make db-reset`.

## Cognito authorization

Feature 1 provisions users in the shared Cognito user pool and assigns the `ADMIN` or `AGENT` group. This service validates the token's RS256 signature against the pool's JWKS, plus its issuer, app-client ID, expiration, and `token_use=access` claim. It rejects ID tokens and tokens without a supported group.

The verified Cognito `sub` is the creator identity. Agents may read, update, verify, and delete only their own client profiles. Admins can access every client. The service ignores caller-supplied agent IDs and replaces the header from the authenticated subject before invoking the existing controller. The audit middleware takes the actor ID from that verified identity, not from request data.

account-service depends on this rule. Before creating an account it calls `GET /api/clients/{id}` with the caller's own token: a 200 gives it the client's `created_by_agent_id` to store as the account's owner, and a 404 means the client is missing or not the caller's.

## Business dashboard figures

`GET /aggregates/clients/stats?from&to[&groupBy=agent]` and `GET /aggregates/clients/action-items[?staleDays]` serve the business dashboard's client figures and work queue. They take the caller's access token, forwarded by the dashboard, and scope by it: agents see their own clients, and per-agent rows are for admins only (403 otherwise). They sit outside `/api` so API Gateway never routes them, and are not audited here: the dashboard audits each view ([ADR-0039](../../docs/adr/ADR-0039-dashboard-aggregate-endpoints.md)). Deleted clients are never counted; statuses are grouped upper-cased because both `UNVERIFIED` and `verified` are stored ([ADR-0038](../../docs/adr/ADR-0038-dashboard-metric-definitions.md)). Contract: [`docs/api/dashboard-aggregates.yaml`](../../docs/api/dashboard-aggregates.yaml).

`GET /aggregates/clients/team-comparison?days=7|30|90` compares the caller's clients onboarded over the last 7, 30 or 90 days (ending today in Singapore) with the team median. The median is computed here so no other agent's figure leaves the service. The group is every agent with a client, not deleted, created in the last 12 months, and the median is withheld when fewer than 5 agents are in it. Other windows are refused, so medians over chosen windows cannot be compared to narrow down one agent's figure (ADR-0038).

## Deleting a client

`DELETE /api/clients/{id}` is a **soft delete** ([ADR-0036](../../docs/adr/ADR-0036-soft-deletion-of-clients-and-accounts.md)), as the requirements Q&A asks: it sets `deleted_at` and keeps the row. From then on every endpoint treats the client as missing, for admins and agents alike: reads, updates, verification and a second delete all return 404. A deleted client's email and phone number can be used to onboard a new client. Nothing restores a deleted client, and deleting is not erasure of the client's personal data ([open item 21](../../docs/architecture/open-items.md#21-erasing-soft-deleted-client-data)).

Accounts used to reference clients through a foreign key with `ON DELETE RESTRICT`. That key cannot cross databases, so the delete keeps the rule by calling `GET /api/accounts?clientId={id}` on account-service with the caller's token. account-service never lists deleted accounts, so only accounts that are not deleted block the delete, including accounts whose status is `closed`:

| account-service answers | Response |
|---|---|
| An empty list | The client is deleted (200) |
| One or more accounts | 409, and nothing is deleted |
| Anything else, or no answer within 2 seconds | 503, and nothing is deleted |

The check and the delete are not atomic, so an account created between them is left on a deleted client. ADR-0035 records this.

Deleted rows are filtered by SQLBoiler's `add-soft-deletes` setting in `sqlboiler.toml` and `sqlboiler.docker.toml`, which makes every generated query skip them. All client queries, including the ownership check, go through the generated code; any hand-written SQL added later must add `deleted_at IS NULL` itself.

## Auditor SQS events

The service records one audit event per completed client endpoint call. Each event records the event type (`CRUD`), normalized action (`CREATE`, `GET`, `UPDATE`, or `DELETE`), authenticated actor, route, HTTP method, status code, timestamp, and client ID when available. It does not copy request or response bodies into the audit event. Configure `AWS_REGION`, `AUDIT_QUEUE_URL`, and AWS credentials through the standard AWS SDK credential chain. For LocalStack, set `AWS_ENDPOINT_URL` to its endpoint.

**Delivery** ([ADR-0020](../../docs/adr/ADR-0020-audit-write-path-outbox-shared-sqs.md)). The audit middleware saves each event to the `audit_outbox` table in a transaction of its own, after the endpoint's change has committed. This is ADR-0020's exception for producers that build their events in request middleware, used because of the cost of moving event creation into the service layer. Every 2 seconds a relay goroutine (`src/serverd/sqs/outbox.go`) takes the oldest waiting events (up to 50, locked with `FOR UPDATE SKIP LOCKED` so replicas take different rows), sends each to SQS, and deletes it once SQS accepts it. An SQS or Auditor outage therefore delays events instead of losing them. Delivery is at least once: a task that dies between the send and the delete sends the event again, and the Auditor drops the repeat by `event_id`. Two gaps remain: a crash between the endpoint's commit and the outbox write loses that event, and an outbox write that fails is logged with its event ID and dropped. The outbox table is written with plain SQL, so `sqlboiler.toml` and `sqlboiler.docker.toml` leave it out of the generated ORM.

Configure API Gateway with a JWT authorizer for the same Cognito issuer and app-client ID as an additional edge check. This service also validates every token itself, so direct service access does not bypass authentication.

## Production transport security

Set `APP_ENV=production`, `APP_TLS_CERT_FILE`, and `APP_TLS_KEY_FILE` to serve HTTPS. Set `DB_SSLMODE=verify-full` and `DB_SSLROOTCERT` to verify PostgreSQL TLS. `ACCOUNT_SERVICE_BASE_URL` must be `https://`, because client deletion forwards the caller's access token to it. The service defaults to production security and refuses to start in production mode if these settings are missing; `make run` explicitly sets `APP_ENV=local`. If Cognito access tokens use resource-bound `aud` claims, also set `COGNITO_API_AUDIENCE` to the expected value. Local HTTP and `sslmode=disable` are intended only for local development.

RDS encryption-at-rest settings still require deployment infrastructure configuration. If saving an audit event fails after an endpoint completes, the service reports the failure in its application log; the completed endpoint response is not changed retroactively.
