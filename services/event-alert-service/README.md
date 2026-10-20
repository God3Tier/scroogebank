# Event Alert Service

Java 25 Spring Boot foundation for Dynamic Event-Driven Client Alerts. The same
artifact supports one explicit mode at a time:

- `ALERT_MODE=API` for authenticated CRM alert operations.
- `ALERT_MODE=WORKER` for bounded scheduled processing.

The default runtime is offline and deterministic. It contains no provider
credentials and makes no outbound provider calls unless an adapter and its
independent enable switch are explicitly configured. Synthetic holdings are loaded
from `src/main/resources/fixtures/holdings-snapshot.v1.json`.

The ingestion core is provider-neutral and outbound-disabled by default with
`ALERT_INGESTION_ENABLED=false`. It builds at most four grouped public-market
queries from instruments held by enough distinct clients, requests at most ten
results per query, applies a five-minute watermark
overlap, retries retryable provider failures at most three times, validates the
approved-domain boundary, canonicalizes and deduplicates URLs, and advances the
watermark only after the complete run succeeds. Retrying a partially persisted
failed window derives its final result count from durable rows, preserving
idempotent replay accounting. Historical replay is restricted to `WORKER` mode,
cannot extend into the future or exceed the configured lookback span, and never
moves the scheduler watermark. The replay is started by an operator as described below. `IngestionJob`
can run only in `WORKER` mode. Supplying the IAM-authenticated AgentCore Gateway client remains
blocked on the approved infrastructure task; the application does not create or
contact AWS resources by itself.

The holdings-derived search universe is calculated only in Singapore. The
synthetic holdings fixture supplies stable instrument IDs; the `instrument`
catalogue maps them deterministically to public ticker, issuer, and sector terms.
By default an instrument must be represented by at least two distinct clients and
5% of clients in the snapshot. Tokyo receives none of those counts or private
holding facts. Unknown/inactive IDs and an empty eligible universe fail closed.

The AI boundary is independently disabled by default with
`ALERT_AI_ENABLED=false` and `ALERT_AI_PROVIDER=none`. The adapters accept public evidence only,
screen prompt-injection/private identifiers before invocation, send versioned
closed-schema requests through `AiModelGateway`, validate citations and candidate
instruments, cap input/output/retries/timeouts, and safely abstain or retain an
event for deterministic rule review. Briefings are explicitly AI-labelled,
source-cited, uncertainty-bearing, and rejected when they contain advice
language.

An opt-in Bedrock Runtime implementation uses the Converse API with the pinned
global Luna inference profile, AWS default credential chain, configured region,
zero temperature, output-token cap, and per-request timeout. Enabling it requires
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
instrument master by exact ticker, issuer or sector name, classifies it once, and
creates the alerts for every agent whose clients hold an affected instrument.
Severity comes from a fixed table by event type. It does nothing while
`ALERT_AI_ENABLED=false`, and a source the model cannot handle yet stays pending
for the next run. The run exits 1 if a source errors. Its summary line is
`worker pipeline examined=… processed=… no_match=… alerts_created=… deferred=… errors=…`.

`ALERT_AGENTCORE_GATEWAY_URL` (and `ALERT_AGENTCORE_REGION`, default
`ap-northeast-1`) switch on the IAM-signed Gateway client; the credentials come
from the task role. With no URL the search fails cleanly as
`PROVIDER_NOT_CONFIGURED`.

An operator replays a past window by starting a one-off worker task with
`ALERT_WORKER_REPLAY_FROM`, `ALERT_WORKER_REPLAY_TO`, `ALERT_WORKER_REPLAY_OPERATOR`
and `ALERT_WORKER_REPLAY_CORRELATION_ID`. All four are required; the worker writes
an `alert.replay-requested` audit event before it searches, and refuses if it
cannot. A replay never moves the scheduler watermark.

`ALERT_CLOUDWATCH_METRICS_ENABLED=true` exports the metrics to CloudWatch as the
worker exits (namespace `ALERT_CLOUDWATCH_NAMESPACE`, default `CRM/EventAlerts`).
It is off by default because every distinct metric and tag combination is billed.

Not done yet (details, owners and suggested fixes in
[`docs/feature-plans/event-driven-alerts_gaps.md`](../../docs/feature-plans/event-driven-alerts_gaps.md)):

- The AgentCore client has only run against a fake gateway, and the pipeline
  only with a fixture model, so the real service's response shape and the real
  model's behaviour are unproven. The smoke test in the operations runbook
  settles both.

Infrastructure needs, for when it is built: an EventBridge Scheduler schedule
every 4 hours that runs the task with `ALERT_MODE=WORKER` and the database
settings, and an alarm when a worker task stops with a non-zero exit code
(ADR-0031: an alarm on failed runs).

## Local verification

Docker must be running because repository tests use PostgreSQL Testcontainers.

```powershell
mvn -B -pl services/event-alert-service -am verify
```

Runtime database settings are supplied through `EVENT_ALERT_DB_URL`,
`EVENT_ALERT_DB_USERNAME`, and `EVENT_ALERT_DB_PASSWORD`. Never commit real
credentials. Flyway owns the schema and Hibernate validates it at startup.

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
