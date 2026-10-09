# user-service

Implements **Feature 1: CRM User Management** — admin/agent enrollment and authentication.

## What it owns

- The `User` table (`users_db`): `id`, `firstName`, `lastName`, `email`, `role` (ADMIN/AGENT), `enabled`, `deletable`, `idp_sub`, timestamps — matches Appendix 2's User attributes plus the bookkeeping fields needed to enforce "root admin cannot be deleted" and to link a local user row to its identity-provider account.
- Provisioning users in the Cognito user pool — this service never stores or hashes a password itself.
- The `audit_outbox` table (`users_db`): audit events saved but not yet sent to the Auditor's queue (see [Audit events](#audit-events)).

## Endpoints

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `/api/users` | ADMIN | Create a user (agent or admin) |
| GET | `/api/users` | ADMIN | List users, paged; optional `role` (`ADMIN`/`AGENT`) and `enabled` filters, `page` (default 0) and `size` (default 20, max 100). Sorted by last name, first name |
| GET | `/api/users/{id}` | ADMIN | Fetch a user |
| GET | `/api/activities` | ADMIN | Audit log across all services, newest first; optional `agentId`, `from`/`to` (ISO-8601, `from` inclusive, `to` exclusive), `size` (default 20, max 100) and `cursor` (the previous page's `nextCursor`) |
| PUT | `/api/users/{id}` | ADMIN | Update first/last name |
| PATCH | `/api/users/{id}/role` | ADMIN | Change role (`{"role":"ADMIN"}` or `AGENT`); root admin: rejected with 403 |
| PATCH | `/api/users/{id}/disable` | ADMIN | Disable a user (root admin: rejected with 403) |
| POST | `/api/users/{id}/reset-password` | ADMIN | Trigger the IdP's password-reset flow |
| POST | `/api/users/me/logout` | Any signed-in user | Record that the caller signed out, for the audit log. Revokes nothing; see [Logout](#logout). No body; 204, or 404 if the token's `sub` has no user here |
| GET | `/api/corrupted-logs` | ADMIN | List corrupted audit logs from the Auditor service |
| POST | `/api/corrupted-logs` | ADMIN | Create a corrupted audit log in the Auditor service |
| DELETE | `/api/corrupted-logs/{id}` | ADMIN | Delete a corrupted audit log from the Auditor service |
| GET | `/api/accounts/{accountId}/transactions` | ADMIN/AGENT | Read transactions for an account the caller may access |
| GET | `/aggregates/transactions/stats?from&to` | ADMIN/AGENT | The business dashboard's transaction figures: every transaction for an admin, the caller's own accounts for an agent |

Full request/response shapes: Swagger UI at `http://localhost:8081/swagger-ui.html` when run locally with the `local` profile. See [API docs (Swagger UI)](../../README.md#api-docs-swagger-ui) in the root README.

`GET /api/accounts/{accountId}/transactions` authenticates the caller here, checks `ADMIN`/`AGENT` access, and forwards the access token to account-service to verify that the requested account belongs to the agent. After that check succeeds, it fetches transactions from the internal SFTP receiver. The receiver must not be exposed as a public API. Configure `ACCOUNT_SERVICE_BASE_URL` and `SFTP_RECEIVER_BASE_URL` for the deployment. Locally, start client-profile-management-service and account-service each with `make run`, start the receiver with `make -C services/sftp up`, then call the user-service at `http://localhost:8081`.

`GET /aggregates/transactions/stats` follows the same pattern for the business dashboard. For an agent it forwards the token to account-service's `GET /aggregates/accounts/ids`, then asks the receiver's private `POST /aggregates/transactions/stats` for figures over those accounts only; for an admin it asks for every transaction. The token never reaches the receiver. The route sits outside `/api` so API Gateway never routes it, and is not audited here: the dashboard audits each view ([ADR-0039](../../docs/adr/ADR-0039-dashboard-aggregate-endpoints.md)).

## Design choices

**Cognito does the security-sensitive work.** OAuth2/OIDC, MFA, and password hashing/storage are Cognito's job, not this service's. `CognitoIdentityProviderClient` uses AWS SDK v2 (`AdminCreateUser`, `AdminAddUserToGroup`, `AdminRemoveUserFromGroup`, `AdminDisableUser`, `AdminResetUserPassword`) against the configured user pool. This service has no login endpoint: users sign in through Cognito's Hosted UI from the frontend. It sits behind an `IdentityProviderClient` interface — one implementation, kept as an interface so the IdP can be swapped without touching controllers or the security config.

Role is sourced from **Cognito group membership** (`Admin`/`Agent` groups), read off the JWT's `cognito:groups` claim — see `security/JwtRoleConverter.java`. This is the single source of role truth; the local `User.role` column mirrors it for query convenience, but the JWT claim is what every service's `@PreAuthorize` actually checks.

**Zero Trust.** `SecurityConfig` makes this service an OAuth2 Resource Server validating every request's JWT against `OIDC_ISSUER_URI` — no session state, and the gateway's own validation is not trusted as sufficient (defense in depth: every service re-validates independently).

**Root admin.** Seeded on first startup by `RootAdminInitializer` (a `CommandLineRunner`) rather than a SQL migration, because seeding requires creating the corresponding Cognito account too (an API call Flyway can't make). Cognito emails the temporary password; MFA enrolment happens on first sign-in. `deletable=false` is enforced in `UserService.disableUser` (403 if violated) — the spec only requires "cannot be deleted," and disabling is the closest destructive-equivalent operation this service exposes, so it's guarded the same way. `UserService.changeRole` applies the same guard: demoting the root admin would leave the system without a guaranteed admin.

**Role changes.** `PATCH /api/users/{id}/role` moves the user between Cognito groups and then updates the local `role` column. It adds the new group before removing the old one, so a failure part-way leaves the user in both groups rather than in none; both calls are idempotent, so repeating the request completes the change. Tokens already issued still carry the old `cognito:groups` claim until they expire and are refreshed, so a demoted admin keeps admin access for up to one access-token lifetime.

<a id="logout"></a>**Logout.** Cognito ends the session itself, but it has no sign-out trigger, so a logout reaches the audit log only if the frontend reports it ([ADR-0025](../../docs/adr/ADR-0025-identity-cognito-mfa-pkce-service-tokens.md)). On sign-out the frontend should:

1. Revoke the refresh token at Cognito's `/oauth2/revoke`. The app client has no secret, so the browser can call it directly.
2. Call `POST /api/users/me/logout` with the session's access token. It is still accepted after step 1: Cognito's revocation only affects Cognito's own APIs, and this service checks tokens locally (signature, expiry, issuer, client).
3. Send the browser to Cognito's `/logout` to end the Hosted UI session, and drop its local tokens.

The endpoint records what the frontend reports; it does not check that a revocation happened. If the call fails or is skipped, the user is still signed out but no `LOGOUT` event is recorded. The frontend should go on to step 3 either way, so an audit failure never blocks a sign-out.

**`idpSub` in responses.** Every user response includes `idpSub`, the user's Cognito `sub`. Other services identify agents by it, not by `id`: the client profile service stores it as `created_by_agent_id`, and audit events carry it as `actor_id`. Without it, an admin view such as the business dashboard's per-agent table cannot match those records to a named user. It is safe to return because every endpoint here is admin-only, and the `sub` is already in every token the user presents. A future non-admin endpoint that returns a user should use a narrower response.

## NFR notes

- **Maintainability/Extensibility**: adding a role means a new Cognito group plus an enum value; swapping the IdP entirely means one new `IdentityProviderClient` implementation — no changes to controllers or the security config.
- **Security**: no password ever touches this service's code or database — see "Cognito does the security-sensitive work" above. User-management actions are sent to the Auditor through an outbox and SQS; audit payloads omit names, email addresses, passwords, and request bodies.
- **Resiliency**: audit events wait in the outbox while SQS or the Auditor is unavailable, and are sent when it recovers; user requests never wait on either.
- **Scalability**: stateless (JWT-based auth, no server-side session) — horizontally scalable behind the gateway with no sticky-session requirement.
- **Activity feed**: `GET /api/activities` does not store audit data. It reads the auditor's log over internal gRPC (`ListAuditLogs`, stubs generated at build time from `services/auditor/proto`). Pages are keyed by sequence number, not offset, so new entries do not shift pages the admin has not read yet. The auditor call has a 3-second deadline, and an auditor failure returns `503`. See "Resiliency scope" in [nfr-notes.md](../../docs/architecture/nfr-notes.md).

## Configuration

| Env var | Source | Purpose |
|---|---|---|
| `DB_HOST` / `DB_PORT` | This service's own RDS instance | Postgres connection |
| `USER_DB_NAME` | `users_db` | Database name |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | Terraform / Secrets Manager | DB credentials |
| `AUDITOR_GRPC_TARGET` | The auditor's Service Connect endpoint, e.g. `auditor:9090` | Activity feed reads |
| `OIDC_ISSUER_URI` | `https://cognito-idp.<region>.amazonaws.com/<pool-id>` | Resource-server JWT validation |
| `COGNITO_USER_POOL_ID`, `AWS_REGION` | Terraform | Cognito admin API access |
| `AUDIT_QUEUE_URL` | ECS task definition / local environment | Auditor's `audit-insert` SQS queue; when empty, no audit events are saved or sent |
| `AUDITOR_BASE_URL` | ECS task definition / local environment | Base URL of the Auditor HTTP service (defaults to `http://localhost:8080`) |
| `ACCOUNT_SERVICE_BASE_URL` | ECS task definition / local environment | Base URL of account-service, used to verify that the caller may access the requested CRM account (defaults to `http://localhost:8083`). Replaces `CLIENT_PROFILE_BASE_URL`, since accounts moved out of client-profile-management-service |
| `SFTP_RECEIVER_BASE_URL` | ECS task definition / local environment | Internal SFTP receiver URL (defaults to `http://localhost:8082`) |
| `ROOT_ADMIN_EMAIL` | `var.root_admin_email` | Seeded root admin's login |

None of these have defaults — a missing variable fails the container at startup rather than falling back to something wrong. All are set by the ECS task definition in `infra/terraform/envs/prod/main.tf`.

This service's task role is the only one granted Cognito admin permissions (`cognito-idp:AdminCreateUser`, `AdminAddUserToGroup`, `AdminRemoveUserFromGroup`, `AdminDisableUser`, `AdminUpdateUserAttributes`, `AdminResetUserPassword`), scoped to the single user pool. It also needs `sqs:SendMessage` scoped to the Auditor's `audit-insert` queue when audit publishing is enabled. AWS credentials use the standard SDK provider chain; no credentials are configured in application properties.

## Audit events

After each user API request completes, the service records an audit event for user creation, retrieval, update, role change, disable, password-reset, and logout operations. A role change is recorded as `ROLE_CHANGE` with the new role in the payload's role field; the previous role is not recorded. A logout is recorded as `LOGOUT`, with the caller's own user as the resource. Corrupted-log proxy operations (`READ`, `CREATE`, `DELETE`) and transaction reads (`READ`) are also recorded, without copying request or response bodies. Each event's `resource_type` says what was acted on: `USER`, `CORRUPTED_AUDIT_LOG`, or `ACCOUNT` for an account's transactions. Reading the corrupted-log list has no resource ID; a created corrupted log's ID is taken from the request, since the Auditor's response does not include it. A request refused by an authorization check is recorded with status 403, and one that fails with an unhandled exception with 500. The successful root-admin bootstrap also publishes a system-attributed create event. Events use `event_type=CRUD` and `source_service=USER_MANAGEMENT`. `actor_id` is the verified Cognito `sub` (or `system:root-admin-initializer` for bootstrap); the payload records the resource ID, endpoint, HTTP method, response status, timestamp, and role where available. It does not copy user names, email addresses, passwords, or response bodies into the queue. This service has no login endpoint because sign-in is handled by Cognito Hosted UI, so login events are outside its request audit scope; they are to come from Cognito's `PostAuthentication` trigger ([ADR-0020](../../docs/adr/ADR-0020-audit-write-path-outbox-shared-sqs.md)). Logouts are recorded only when the frontend reports them (see [Logout](#logout)).

**Delivery** ([ADR-0020](../../docs/adr/ADR-0020-audit-write-path-outbox-shared-sqs.md)). `AuditEventPublisher` saves each event to the `audit_outbox` table in a transaction of its own, after the request's change has committed. This is ADR-0020's exception for producers that build their events in request middleware, used because of the cost of moving event creation into the service layer; the ADR's default writes the row in the business transaction. Every 2 seconds `AuditOutboxRelay` takes the oldest unsent rows (up to 50, locked with `FOR UPDATE SKIP LOCKED` so replicas take different rows), sends each to SQS, and deletes it once SQS accepts it. An SQS or Auditor outage therefore delays events instead of losing them. Delivery is at least once: a task that dies between the send and the delete sends the event again, and the Auditor drops the repeat by `event_id`. Two gaps remain, and neither changes the API response: a crash between the request's commit and the outbox write loses that event, and an outbox write that fails is logged with its event ID and dropped. Set `AUDIT_QUEUE_URL` to the standard `audit-insert` queue to enable auditing; without it, no rows are written. The Auditor accepts the user-service payload while continuing to accept its existing generic CRUD payload during rollout.

## Deploying

Built and pushed as part of the repo-wide deploy — see the root README. To roll just this service after an image push:
```bash
aws ecs update-service --cluster scrooge-crm-cluster --service user-service --force-new-deployment
```

Release the Auditor no later than this service. The Auditor rejects actions and resource types it does not know, so events this service sends before the Auditor knows them go to the dead-letter queue.

**Tests**:
```bash
make test
```
`UserRepositoryTest` uses Testcontainers to spin up a real Postgres and verifies unique-constraint enforcement on email/idp_sub. Needs Docker running locally; it does not run the service itself.

## Local development

The `local` Spring profile selects the developer PostgreSQL container and enables Swagger. It does **not** disable Cognito provisioning, JWT validation, or Admin-only method authorization. Cognito and AWS credentials are required locally as well as in deployed environments.

Set the user-pool configuration, then start the database and application:

```bash
export OIDC_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id>
export COGNITO_USER_POOL_ID=<user-pool-id>
export COGNITO_APP_CLIENT_ID=<app-client-id>
export AWS_REGION=<region>
export ROOT_ADMIN_EMAIL=<root-admin-email>
make run
```

The AWS SDK uses its normal credential provider chain for Cognito administration. The API listens at `http://localhost:8081`; Swagger UI is available at `http://localhost:8081/swagger-ui.html`. Local HTTP is for loopback development only.

Use a Cognito **access token** from an `ADMIN` group member for the user-management API commands:

```bash
make health
make create-user TOKEN="$TOKEN" FIRST_NAME=Alice LAST_NAME=Agent EMAIL=alice.local@example.test ROLE=AGENT
make get-user TOKEN="$TOKEN" USER_ID=<id-from-create-response>
make update-user TOKEN="$TOKEN" USER_ID=<id-from-create-response> FIRST_NAME=Alice LAST_NAME=Updated
make disable-user TOKEN="$TOKEN" USER_ID=<id-from-create-response>
```

To stop the local database, run `make down`; to reset it and delete its data, run `make local-db-reset`. `make package`, `make clean`, `make image`, and `make test` provide the build and test workflows.

## Verifying it works

Against a deployed environment, with `$API` set to the ALB/CloudFront API base URL:

Sign in to the frontend as the root admin, then copy the access token from the browser into `$TOKEN`. See [Getting an access token](../../README.md#getting-an-access-token) in the root README. There is no scripted sign-in.

```bash
curl -X POST "$API/api/users" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"firstName":"Alice","lastName":"Agent","email":"alice@scroogebank.com","role":"AGENT"}'
```
Expect `201 Created` with the new user's JSON, and the user to appear in the Cognito pool's `Agent` group. Repeating with an AGENT's token instead of an ADMIN's should return `403 Forbidden`.
