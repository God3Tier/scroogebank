# Local CRM stub and database-mode check

A stand-in for the CRM directory and Cognito, so the alert service can be tried in
`ALERT_HOLDINGS_MODE=database`. Synthetic data only; developer machines only.
Use the real-service checks below to verify the CRM integration.

```sh
mvn -pl services/event-alert-service -DskipTests package
docker run -d --name alert-e2e-pg -e POSTGRES_USER=event_alert -e POSTGRES_PASSWORD=e2e \
  -e POSTGRES_DB=event_alert -p 55432:5432 postgres:16-alpine     # use a fresh database
node scripts/local-crm-stub/e2e.mjs                               # needs ports 9099 and 18080 free
docker rm -f alert-e2e-pg
```

If port 55432 is unavailable, set `ALERT_E2E_DB_PORT` to a free port and use
that port in the `docker run -p` mapping (for example, `5438:5432`). The three
real-service scripts below read the same variable and start their own database.

`e2e.mjs` starts the stub and the packaged jar, then checks 19 things: sign-in and roles,
record/replay/stale/unknown-instrument/savings-account refusals, restart persistence,
reassignment, CRM outage (503), audit contents, and a one-shot worker run.
It cannot create alerts: that needs a search provider and a model, which stay off.

## Against the real account-service

`e2e-real-account.mjs` runs the **real account-service** (built from its Dockerfile, with its own PostgreSQL and
schema) and only fakes Cognito: the stub serves keys and mints tokens over HTTPS under the `cognito-idp` host name
the Go service requires, using a throw-away certificate made with OpenSSL. It needs Docker, Node, OpenSSL and
free ports 9099, 18080, 18083 and the selected alert database port.

```sh
node scripts/local-crm-stub/e2e-real-account.mjs
```

It checks the real route's security (no token, the import's token, the wrong scope, a user token), the owner the
route returns, the alert service's behaviour on top of it, a reassignment through the real `/ownership/accounts`
route, closed and deleted accounts, an outage, restart persistence and a one-shot worker run.

The alert API forwards the agent's own token to account-service on holdings writes, so these scripts give the
JVM a throw-away truststore and a hosts-file override for the `cognito-idp` name, and the stand-in publishes port 443
(it must be free). Tokens for the alert API are minted under that https issuer.

## Through the real client-service

`e2e-reassignment.mjs` adds the **real client-service** (own PostgreSQL) beside the real account-service. Only
Cognito and user-service (`stub-user-service.mjs`) are stand-ins. An admin reassigns a client through
`POST /api/clients/reassignments`; the script checks the client, both accounts and the alert service's access
all move, and that an unknown receiver moves nothing. Needs the client-service ORM generated once
(`sqlboiler` step of its `make test`, git-ignored).

```sh
node scripts/local-crm-stub/e2e-reassignment.mjs      # also needs port 18084 free
```

## Live search (uses real search credits)

`e2e-live-generation.mjs` records a real, searchable instrument (DBS, `SGX-D05`) through the API, then runs the
worker once against the real Tavily API with the model off. The key comes only from the environment:

```sh
TAVILY_API_KEY=... node scripts/local-crm-stub/e2e-live-generation.mjs     # a few search credits per run
```

It proves the maintained portfolio drives a real search. It cannot create an alert: with the model off the
pipeline skips source processing, and the model needs AWS.
