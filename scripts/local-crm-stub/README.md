# Local CRM stub and database-mode check

A stand-in for the CRM directory routes and Cognito, so the alert service can be tried in
`ALERT_HOLDINGS_MODE=database` before the real CRM routes exist (CH-01). Synthetic data
only; developer machines only. It does **not** replace the real-CRM test the plan asks for.

```sh
mvn -pl services/event-alert-service -DskipTests package
docker run -d --name alert-e2e-pg -e POSTGRES_USER=event_alert -e POSTGRES_PASSWORD=e2e \
  -e POSTGRES_DB=event_alert -p 55432:5432 postgres:16-alpine     # use a fresh database
node scripts/local-crm-stub/e2e.mjs                               # needs ports 9099 and 18080 free
docker rm -f alert-e2e-pg
```

`e2e.mjs` starts the stub and the packaged jar, then checks 19 things: sign-in and roles,
record/replay/stale/unknown-instrument/savings-account refusals, restart persistence,
reassignment, CRM outage (503), audit contents, and a one-shot worker run.
It cannot create alerts: that needs a search provider and a model, which stay off.

## Against the real account-service

`e2e-real-account.mjs` runs the **real account-service** (built from its Dockerfile, with its own PostgreSQL and
schema) and only fakes Cognito: the stub serves keys and mints tokens over HTTPS under the `cognito-idp` host name
the Go service requires, using a throw-away certificate made with OpenSSL. It needs Docker, Node, OpenSSL and
free ports 9099, 18080, 18083 and 55432.

```sh
node scripts/local-crm-stub/e2e-real-account.mjs
```

It checks the real route's security (no token, the import's token, the wrong scope, a user token), the owner the
route returns, the alert service's behaviour on top of it, a reassignment through the real `/ownership/accounts`
route, closed and deleted accounts, an outage, restart persistence and a one-shot worker run.
