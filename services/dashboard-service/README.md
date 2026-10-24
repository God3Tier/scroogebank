# dashboard-service

The business dashboard (x-factor 2): per-agent and bank-wide figures for admins, and a scoped portfolio and work queue for agents.

Feature plan: [`docs/feature-plans/business-dashboard_plan.md`](../../docs/feature-plans/business-dashboard_plan.md). API: [`docs/api/dashboard-api.yaml`](../../docs/api/dashboard-api.yaml). What it fetches: [`docs/api/dashboard-aggregates.yaml`](../../docs/api/dashboard-aggregates.yaml). Decisions: [ADR-0033](../../docs/adr/ADR-0033-xf2-dashboard-topology-cache-audit.md), [ADR-0038](../../docs/adr/ADR-0038-dashboard-metric-definitions.md), [ADR-0039](../../docs/adr/ADR-0039-dashboard-aggregate-endpoints.md).

## How it works

**It owns no data.** There is no database. Every figure is computed by the service that owns the data, on its private `/aggregates` routes, and fetched with the caller's own token:

| Figures | From |
|---|---|
| Agent roster (admins only) | user-service `GET /api/users` |
| Clients, the work queue and an agent's team comparison | client-profile-management-service `GET /aggregates/clients/*` |
| Accounts | account-service `GET /aggregates/accounts/stats` |
| Transactions | user-service `GET /aggregates/transactions/stats`, in front of the SFTP receiver |

So the dashboard can read nothing the caller could not, and each service scopes its own answer: an agent sees only their own clients, accounts and matched transactions. No endpoint takes an agent ID.

- **Panels degrade one at a time.** Upstream calls run in parallel (2 s each, 3 s in total). A failing or unconfigured upstream makes its panels `UNAVAILABLE` with a reason; the response is 200 unless every panel failed (503). An upstream that times out, answers with an error or cannot be reached is logged as a `WARN` naming the panel and the cause.
- **Warm-up:** at startup, before the task takes traffic, the service calls each configured upstream's health endpoint once, without a token, and logs `Warm-up: <service> took N ms`. The first dashboard then does not pay the HTTP client, TLS and JSON first-use costs, which on Fargate made every call of the first page take 1.5 to 2 s. A down upstream is logged, never fatal.
- **Cache:** each task caches each caller's response for 3 minutes. `generatedAt` says when the figures were computed. A response with an `upstream_timeout` or `upstream_error` panel is not cached, so the next request asks again.
- **Audit:** every view, cache hits included, sends a `DASHBOARD_VIEW` event to the shared audit queue, best-effort. The auditor accepts `source_service: DASHBOARD`.
- **Days** are Singapore days; "today" is the Singapore date.
- **Team comparison:** an agent's overview compares their clients onboarded with the team median. client-service computes it, so no other agent's figure reaches the dashboard. It covers only the last 7, 30 or 90 days ending today; any other window shows the panel as `unsupported_window`. With fewer than 5 agents compared, there is no median (ADR-0038).

## Running it locally

From the repository root, with Maven (or in `maven:3-eclipse-temurin-25`):

```sh
OIDC_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id> \
COGNITO_APP_CLIENT_ID=<crm-app-client-id> \
USER_SERVICE_BASE_URL=http://localhost:8081 \
CLIENT_SERVICE_BASE_URL=http://localhost:8080 \
ACCOUNT_SERVICE_BASE_URL=http://localhost:8083 \
  mvn -pl services/dashboard-service spring-boot:run -Dspring-boot.run.profiles=local
```

Then:

- `http://localhost:8085/actuator/health` → `{"status":"UP"}`, unauthenticated
- `http://localhost:8085/swagger-ui.html` → the API spec, served only under the `local` profile

Leave out a base URL and its panels report `upstream_not_deployed`; that is the degradation path working. For transaction figures, user-service also needs `ACCOUNT_SERVICE_BASE_URL` and `SFTP_RECEIVER_BASE_URL`, and the SFTP stack must be running.

## Configuration

| Variable | Purpose |
|---|---|
| `OIDC_ISSUER_URI` | Cognito user pool issuer. Required |
| `COGNITO_APP_CLIENT_ID` | The CRM app client. Only access tokens issued to it are accepted. Required |
| `USER_SERVICE_BASE_URL` | Roster and transaction figures. Blank: those panels are unavailable |
| `CLIENT_SERVICE_BASE_URL` | Client figures and work queue. Blank: unavailable |
| `ACCOUNT_SERVICE_BASE_URL` | Account figures. Blank: unavailable |
| `AUDIT_QUEUE_URL` | The shared audit queue. Blank: no audit events are sent (local use only) |
| `AWS_REGION` | For SQS. Default `ap-southeast-1` |

Tunables in `application.yml`:

| Key | Default | Why |
|---|---|---|
| `dashboard.window.default-days` | 30 | Window when the caller gives no dates |
| `dashboard.window.max-days` | 365 | Bounds what one request asks an upstream to aggregate |
| `dashboard.cache.ttl-seconds` | 180 | Per-task cache lifetime (ADR-0033) |
| `dashboard.cache.max-entries` | 1000 | A starting value, not a measured one |
| `dashboard.upstream.per-call-timeout-ms` | 2000 | Per-upstream budget. Raised to 10000 for timeouts that turned out to be a stall in user-service, then returned to 2000 once that was fixed (ADR-0033) |
| `dashboard.upstream.overall-budget-ms` | 3000 | Whole-request budget. Keep it under API Gateway's 29 s integration timeout, or the caller gets the gateway's error instead of a partial dashboard |

## Port

8085. The others: client-profile-management-service 8080, user-service 8081, receiver API 8082, account-service 8083.

## Tests

```sh
mvn -B verify -pl services/dashboard-service -am
```

No Docker is needed: there is no database, and upstreams are mocked with `MockRestServiceServer`. Tests avoid a `src/test/resources/application.yml`, which would shadow the real one; overrides live in `@DashboardTest`.

## Security

- Stateless resource server. Tokens must be Cognito access tokens issued to the CRM app client (`token_use` and `client_id` are checked), so another app client's token, such as the transaction import's, is refused.
- `cognito:groups` map to `ROLE_*`. `/api/dashboard/agents` is admin-only.
- The decoder is built from the pool's key URL and fetches nothing until the first token arrives.
- `/actuator/health` and the springdoc paths are the only unauthenticated routes.
