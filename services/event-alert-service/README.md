# Event Alert Service

Java 25 Spring Boot foundation for Dynamic Event-Driven Client Alerts. The same
artifact supports one explicit mode at a time:

- `ALERT_MODE=API` for authenticated CRM alert operations.
- `ALERT_MODE=WORKER` for bounded scheduled processing.

The default runtime is offline and deterministic. It contains no provider
credentials and makes no outbound provider calls unless an adapter and its
independent enable switch are explicitly configured. Synthetic holdings are loaded
from `src/main/resources/fixtures/holdings-snapshot.v1.json`.
Creating a CRM user or inserting a database holding does not automatically change
this snapshot; live CRM holdings synchronization is not implemented by this MVP.

The ingestion core is provider-neutral and outbound-disabled by default with
`ALERT_INGESTION_ENABLED=false`. It builds at most four single-issuer public-market
queries from instruments held by enough distinct clients, requests at most ten
results per query, applies a five-minute watermark
overlap, makes at most three attempts per query with monthly quota reserved for
each attempt, validates the approved-domain boundary, canonicalizes and
deduplicates URLs, and advances the
watermark only after the complete run succeeds. Retrying a partially persisted
failed window derives its final result count from durable rows, preserving
idempotent replay accounting. Historical replay is restricted to `WORKER` mode,
cannot extend into the future or exceed the configured lookback span, and never
moves the scheduler watermark. The replay is started by an operator as described
below. `IngestionJob` can run only in `WORKER` mode. Tavily is the sole live-news
adapter; without its key the worker fails closed and makes no outbound search.

The holdings-derived search universe is calculated only in Singapore. The
global synthetic fixture supplies 20 opaque clients across 16 real public
instruments on SGX, NASDAQ, NYSE, LSE, and HKEX, plus the legacy synthetic
instrument used by downstream pipeline tests. The `instrument` catalogue maps
stable IDs deterministically to public ticker, issuer, and sector terms. By
default an instrument is eligible when it is held by at least two clients and
20% of clients in the snapshot, or reaches 50% of one account's positive SGD
value. Tavily receives none of those counts or private holding facts.
Unknown/inactive IDs and an empty eligible universe fail closed.
Each selected instrument contributes governed aliases and official domains.
Results persist only when the title contains a controlled event phrase and
names the issuer or an approved alias. A governed issuer/exchange domain may
carry the issuer name in its excerpt when the event remains explicit in the
title. Ticker-only, sector-only, product-only, cross-issuer and explicit
event-preview results are rejected locally.

The AI boundary is independently disabled by default with
`ALERT_AI_ENABLED=false` and `ALERT_AI_PROVIDER=none`. The adapters accept public evidence only,
screen prompt-injection/private identifiers before invocation, send versioned
closed-schema requests through `AiModelGateway`, validate citations and candidate
instruments, cap input/output/retries/timeouts, and safely abstain or retain an
event for deterministic rule review. Briefings are explicitly AI-labelled,
source-cited, uncertainty-bearing, and rejected when they contain advice
language.

An opt-in Bedrock Runtime implementation uses the Converse API with the pinned
`global.amazon.nova-2-lite-v1:0` inference profile from the Singapore source
Region, AWS default credential chain, zero temperature, output-token cap, and
per-request timeout. Global inference may process the request in another supported
AWS Region. Enabling it requires
both `ALERT_AI_PROVIDER=bedrock` and `ALERT_AI_ENABLED=true`; keep both disabled
until ADR-0010 is accepted and the benchmark gates pass. The committed tests mock
the SDK and make no AWS call. Never put credentials in repository configuration.
Measured gateway responses retain only output JSON and low-cardinality operational
metadata: token counts, latency, and stop reason. The offline evaluation package
turns complete versioned run files into explicit pass/fail benchmark reports.

Outbound ingestion and model controls are independently disabled by default.
The provider-neutral operations layer enforces per-run/monthly quotas and a
monthly estimated-cost stop, exposes low-cardinality Micrometer metrics, and
persists a redacted audit-outbox event before human re-enable. See
`docs/runbooks/event-driven-alerts-operations.md` for configuration and current
integration limits.

Accepted source results can now run through the complete local processing path:
strict classification, citation-bearing briefing, synthetic holdings lookup,
deterministic exposure/materiality scoring, scoped alert persistence, factors,
and durable audit events. Replaying the same source/model/client/instrument tuple
does not create another alert, and generation stops before persistence when the
bounded audit backlog has insufficient capacity.

The audit publisher is disabled by default with
`ALERT_AUDIT_PUBLISHER_ENABLED=false`. When deliberately enabled for the local
auditor, it leases at most 50 rows, publishes the auditor-compatible envelope to
the `audit-insert` SQS queue, and marks delivery only after SQS acknowledges the
send. The auditor stores the source event ID under a unique constraint for
deduplication; expired
leases are reclaimable and retries stop after the configured maximum. The auditor
explicitly allowlists alert event actions. Unit and consumer contract tests verify
the SQS envelope, acknowledgement, retry, validation, and deduplication behavior
without requiring a cloud connection.

A bounded hourly retention job deletes expired briefing content and redacts
expired source title/URL/domain/publication content while preserving stable IDs,
deduplication hashes, relational integrity, and every audit-outbox record.

The read adapter requires an assigned-agent subject and never returns another
agent's positions. `materiality-v1` uses fixed-decimal, versioned weights and a
60.00 threshold. Stale valuations carry a visible 10-point penalty; missing or
expired valuations fail closed and cannot produce an alert score. These are MVP
prototype controls, not investment policy.

## Worker mode

`ALERT_MODE=WORKER` runs one ingestion window and exits (ADR-0007; ADR-0031
decision 1). EventBridge Scheduler is to start it as an ECS task every 4 hours.

- The window ends when the task starts. It begins where the last successful
  window ended, less the 5-minute overlap, or 4 hours back on the first run.
- The worker starts no web server and no timers. The API process runs the audit
  publisher and retention cleanup; it also publishes any audit rows the worker
  leaves in the shared outbox.
- Each run logs one line with counts and codes only, never search terms, titles
  or URLs:
  `worker run status=SUCCEEDED run_id=… window_from=… window_to=… search_attempts=4 accepted=7 duplicates=1 rejected=0 error_code=null`
- The exit code is 1 when the window failed (`FAILED`) or the worker could not
  start, for example with the database unreachable. Every other status exits 0:
  `DISABLED`, `CONTROL_BLOCKED`, `NO_QUERIES`, `ALREADY_RUNNING`,
  `ALREADY_SUCCEEDED` and `SUCCEEDED`.

The monthly search and token quotas, the estimated-cost stop and an operator's
disable are kept in alerts-db (`outbound_usage`, `outbound_control`), so the API
and every worker run share them. Quota reservations are atomic, so two processes
cannot take more than the quota. Each run also holds a PostgreSQL advisory lock
for its whole duration; a run that cannot take it ends as `ALREADY_RUNNING`
without searching or using quota, whether it is scheduled, manual or a replay.

After each window the worker runs a source-to-alert pipeline ([ADR-0049](../../docs/adr/ADR-0049-alert-source-to-alert-pipeline.md)).
It takes the oldest stored sources not yet finished (at most
`ALERT_PIPELINE_MAX_SOURCES_PER_RUN`, default 50), matches each to the
instrument master by exact ticker, issuer name, approved issuer alias or sector name, classifies it once, and
creates the alerts for every agent whose clients hold an affected instrument.
Severity comes from a fixed table by event type. It does nothing while
`ALERT_AI_ENABLED=false`, and a source the model cannot handle yet stays pending
for the next run. The run exits 1 if a source errors. Its summary line is
`worker pipeline examined=… processed=… no_match=… alerts_created=… deferred=… errors=…`.

`ALERT_SEARCH_PROVIDER=tavily` and `ALERT_TAVILY_API_KEY` switch on the selected
live-news adapter (ADR-0050). In AWS the key is injected from the worker-owned
Secrets Manager secret; never commit or place it in Terraform. Requests use
Tavily's restrictive domain mode and published-date filter. Each query combines
that issuer's official domains with optional shared publishers from
`ALERT_INGESTION_APPROVED_DOMAINS`; deployment keeps the shared list empty until
ADR-0011's terms review is recorded. Results are
checked again for approved HTTPS domains, the exact UTC window, count and body
size before persistence. With no key the search fails cleanly as
`PROVIDER_NOT_CONFIGURED`.

The search code is not limited to the seeded tickers or exchanges. A new
instrument becomes searchable when its active instrument-master row supplies a
stable ID, ticker, legal issuer name, approved aliases and official domains and
the holdings-derived thresholds select it. An unknown holding ID or an inactive
row is deliberately skipped rather than searched with guessed metadata.

An operator replays a past window by starting a one-off worker task with
`ALERT_WORKER_REPLAY_FROM`, `ALERT_WORKER_REPLAY_TO`, `ALERT_WORKER_REPLAY_OPERATOR`
and `ALERT_WORKER_REPLAY_CORRELATION_ID`. All four are required; the worker writes
an `alert.replay-requested` audit event before it searches, and refuses if it
cannot. A replay never moves the scheduler watermark.

`ALERT_CLOUDWATCH_METRICS_ENABLED=true` exports the metrics to CloudWatch as the
worker exits (namespace `ALERT_CLOUDWATCH_NAMESPACE`, default `CRM/EventAlerts`).
It is off by default because every distinct metric and tag combination is billed.

The 2026-10-29 local real-key repeat proved the worker/Tavily transport, date and
domain checks, persistence, deduplication and single-issuer relevance gate. Its
small retrospective set measured 7/8 Recall@10 and 8/8 manually actionable
precision. A separate unseen-instrument check exercised Toyota, BHP, SAP and
TSMC metadata and found one completed TSMC profit event while rejecting
unrelated and preview results. Keep ingestion disabled: the labels were not
independently curated, commercial-source terms and billed-credit evidence remain
open. See the [benchmark report](../../docs/testing/tavily-global-portfolio-benchmark-2026-10-29.md).

Infrastructure needs, for when it is built: an EventBridge Scheduler schedule
every 4 hours that runs the task with `ALERT_MODE=WORKER` and the database
settings, and an alarm when a worker task stops with a non-zero exit code
(ADR-0031: an alarm on failed runs).

## Local verification

Docker must be running because repository tests use PostgreSQL Testcontainers.

```powershell
mvn -B -pl services/event-alert-service -am verify
```

## Run a live Tavily pull locally

Start a disposable PostgreSQL database (choose another host port if `5438` is
already used):

```powershell
docker run --name event-alert-postgres --rm -d `
  -e POSTGRES_DB=event_alert `
  -e POSTGRES_USER=event_alert `
  -e POSTGRES_PASSWORD=local-only `
  -p 5438:5432 postgres:16-alpine
```

Set the worker environment in the same PowerShell session. Paste the key only
into your local shell or an approved secret store; never write it to the repo.

```powershell
$env:ALERT_MODE = 'WORKER'
$env:EVENT_ALERT_DB_URL = 'jdbc:postgresql://localhost:5438/event_alert'
$env:EVENT_ALERT_DB_USERNAME = 'event_alert'
$env:EVENT_ALERT_DB_PASSWORD = 'local-only'
$env:ALERT_INGESTION_ENABLED = 'true'
$env:ALERT_SEARCH_PROVIDER = 'tavily'
$env:ALERT_TAVILY_API_KEY = '<your-tavily-key>'
$env:ALERT_AI_ENABLED = 'false'
$env:ALERT_AI_PROVIDER = 'none'
mvn -B -f services/event-alert-service/pom.xml spring-boot:run
```

The worker runs one bounded window and exits. A successful pull logs
`worker run status=SUCCEEDED`; it never logs the key, query text, titles, or
URLs. With AI disabled, sources are stored but alert generation is deferred.
Afterwards, clear the shell variables and stop the disposable database:

```powershell
'ALERT_MODE','EVENT_ALERT_DB_URL','EVENT_ALERT_DB_USERNAME',
'EVENT_ALERT_DB_PASSWORD','ALERT_INGESTION_ENABLED','ALERT_SEARCH_PROVIDER',
'ALERT_TAVILY_API_KEY','ALERT_AI_ENABLED','ALERT_AI_PROVIDER' |
  ForEach-Object { Remove-Item "Env:$_" -ErrorAction SilentlyContinue }
docker stop event-alert-postgres
```

Runtime database settings are supplied through `EVENT_ALERT_DB_URL`,
`EVENT_ALERT_DB_USERNAME`, and `EVENT_ALERT_DB_PASSWORD`. Never commit real
credentials. Flyway owns the schema and Hibernate validates it at startup.
Locally and in tests, Spring Boot's Flyway applies `db/migration` on startup.
In AWS, the task's `db-migrate` container applies the same files before the
service starts, and the task definition sets `SPRING_FLYWAY_ENABLED=false`:
the service connects as its own login, `event_alert_service`, which cannot
change the schema ([ADR-0028](../../docs/adr/ADR-0028-cicd-migrations-terraform.md)
decision 3). A worker deployed later must set the same.

API mode also requires `COGNITO_ISSUER_URI` and `COGNITO_APP_CLIENT_ID`, and
refuses to start without the app client ID. It accepts only Cognito access
tokens issued to that app client (`token_use` `access` and its `client_id`), as
the other CRM services do (ADR-0005, ADR-0025): an ID token or another app
client's token gets `401`. JWT subjects identify actors and
the `cognito:groups` claim supplies the `AGENT` or `ADMIN` role. Agents can post
decisions and feedback only for assigned alerts; admins are read-only. Mutation
requests require `Idempotency-Key`, accept `X-Correlation-Id`, and return `503`
without committing the mutation when durable audit persistence is unavailable.
Successful decision responses include the audited `previousStatus` and
`newStatus`, so clients do not need to infer lifecycle transitions.
The test profile uses a non-routable example issuer and mocked/local security;
local verification does not contact Cognito or any AWS service.

Local auditor delivery requires the auditor's LocalStack `audit-insert` queue.
Set `AUDIT_QUEUE_URL`, `AWS_REGION`, and (for LocalStack only)
`AWS_ENDPOINT_URL`, then set `ALERT_AUDIT_PUBLISHER_ENABLED=true`. The default
build and test path opens no SQS connection.

`GET /api/alerts` returns a stable, cursor-paginated list. Agent results are
scoped in the database query to the authenticated subject; admins receive the
global read-only view. Optional `status`, `minimumScore`, `cursor`, and `limit`
parameters match the OpenAPI contract.

`GET /api/alerts/{alertId}` applies the same database-level visibility rule and
returns exposure, ordered explanation factors, public-source evidence, the
structured briefing when available, and the latest recorded decision. Alerts
outside an agent's assignment scope return `404` to prevent enumeration.

## Container image

Build from the repository root, which is the build context for every service
image:

```powershell
docker build -f services/event-alert-service/Dockerfile -t event-alert-service:local .
```

The image runs as a non-root user, and the same image serves both modes. Pass
the `EVENT_ALERT_DB_*` settings to each; from a container, a database on the
host is at `host.docker.internal`.

- API: add `COGNITO_ISSUER_URI` and `COGNITO_APP_CLIENT_ID`, and publish port
  8080 (`-p 8080:8080`).
  `GET /actuator/health` needs no token.
- Worker: add `ALERT_MODE=WORKER`. The container runs one window and exits with
  the worker's exit code.
