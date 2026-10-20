# Event-driven alerts: gaps found while building the worker

- Recorded: 2026-10-14, against `dev` at `846dd78`
- Found while building task EDA-D1-06 (the run-once worker and the container image, branch `task/event-driven-alerts/worker-runner`) in [the feature plan](event-driven-alerts_plan.md)
- Recorded by: Neo Jia Wen
- Owners: Azeez (search, AI, scheduled worker, operations) and Hayyun (domain, migrations), as in section 19.1 of the feature plan
- Related decisions: [ADR-0031](../adr/ADR-0031-xf1-alert-processing-search-region-boundary.md) and [ADR-0007](../adr/ADR-0007-alert-service-runtime-boundary.md), both Proposed

**Update 2026-10-20:** gaps 3 and 4 are fixed on `task/event-driven-alerts/persist-controls` (migration V3, `JdbcOutboundControlStore`, `PostgresIngestionRunLock`; ADR-0014 amendment). Gaps 1, 2 and 5 remain.

None of these gaps is a risk today. Ingestion and the model are off by default, and nothing is deployed. Each one blocks switching part of the feature on. EDA-D1-06 fixes none of them; each needs a choice its owner should make.

| # | Gap | Blocks | Owner |
|---|---|---|---|
| 1 | A worker run never creates alerts | Any alert from real news | Hayyun and Azeez (EDA-D2-06) |
| 2 | No AgentCore search client | Any search | Azeez (EDA-D1-02, needs AWS) |
| 3 | ~~Monthly controls reset in every worker run~~ **Fixed 2026-10-20** | Nothing | Azeez, with a migration from Hayyun |
| 4 | ~~Two runs at once both search~~ **Fixed 2026-10-20** | Nothing | Azeez |
| 5 | Worker metrics are not exported | ADR-0031's run metrics | Azeez (EDA-D1-05, needs AWS) |

Suggested order: 2 when AWS access exists, then 1. Gap 5 comes with the CloudWatch work. Gaps 3 and 4 are done.

## 1. A worker run never creates alerts

**What happens.** A worker run searches, then saves each result as a `source_result` row: URL, title, domain and publication time. It stops there. Nothing calls [`SourceResultProcessor.process`](../../services/event-alert-service/src/main/java/bank/alert/processing/SourceResultProcessor.java), the step that classifies a result, writes the briefing and creates alerts.

**Why the worker cannot simply call it.** A [`SourceProcessingRequest`](../../services/event-alert-service/src/main/java/bank/alert/processing/SourceProcessingRequest.java) needs values that nothing produces yet:

| Field | Missing because |
|---|---|
| `excerpt` (required, not blank) | Neither `PublicSearchResult` nor `source_result` holds any text from the article, only a title and a content hash. The search contract would need to carry a snippet. |
| `candidateInstrumentIds` (at least one) | Nothing matches a news item to instruments yet (ADR-0032's deterministic matching). |
| `assignedAgentSubject` | The request takes one agent. Someone must decide how one news item reaches every agent whose clients hold an affected instrument. |
| `eventSeverity`, `retrievalRelevance` | Nothing calculates them yet. |

**Effect.** Even with search working, no agent sees an alert from real news. The local path from a source result to an alert works and is tested, but only when a test supplies those values.

**Why it was left.** Each missing value is a design choice in the domain and AI areas. The feature plan's section 19.3 asks both developers to approve contract changes. This is the "source-to-alert E2E" part of EDA-D2-06.

**Suggested fix.**
1. Add a snippet to the search contract.
2. Match instruments deterministically.
3. Decide how one event reaches each agent's clients.
4. Then call the processor from the worker after ingestion.

Record the fan-out choice in the feature plan, or in an ADR if it changes the data boundary.

## 2. No AgentCore search client

**What happens.** [`AgentCoreGatewayClient`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/AgentCoreGatewayClient.java) is an interface with no implementation, so `AgentCoreSearchProvider` is never created. In its place, [`SearchProviderConfiguration`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/SearchProviderConfiguration.java) supplies a provider that throws `PROVIDER_NOT_CONFIGURED`.

**Effect.** With `ALERT_INGESTION_ENABLED=true`, every worker run fails and exits with code 1. It fails cleanly: the run is recorded as `FAILED`, no results are invented, and the watermark does not move. This matches ADR-0031's validation case of a run with Tokyo unavailable. `WorkerApplicationTest` checks this.

**Why it was left.** The client needs the Tokyo AgentCore gateway and IAM-signed calls (EDA-D1-02, blocked on infrastructure). Open item 14 must also confirm that the service is available in Tokyo.

**Suggested fix.** Implement `AgentCoreGatewayClient` with the AWS SDK and default credentials, and create it as a bean only when ingestion is enabled. Test it against a fake gateway, as the other adapters are tested.

## 3. Monthly controls reset in every worker run

**Fixed 2026-10-20.** Usage and disabled state now live in alerts-db (`outbound_usage`, `outbound_control`, migration V3) behind `OutboundControlStore`. Reservations are atomic, so the API and every worker run share one count. The text below records the gap as found.

**What happens.** [`OutboundControlService`](../../services/event-alert-service/src/main/java/bank/alert/operations/OutboundControlService.java) keeps its state in fields in memory:

- the search count, against the quota of 1,000 a month;
- the model token count, against 200,000 a month;
- the estimated cost, against the budget stop of USD 25;
- any capability disabled by an operator, a quota or the budget.

The worker is a new JVM every 4 hours, so each run starts with every count at zero and nothing disabled.

**Effect.**

- Only the cap of 4 searches per run still holds. Six scheduled runs a day use at most 744 searches in a 31-day month (6 × 4 × 31), under the quota of 1,000. Manual runs and replays add more, with no monthly limit.
- The budget stop never fires, because the cost starts at zero in each run.
- A disable lasts only as long as the process that set it. The API process has its own separate copy, so a disable made there never reaches the worker.
- Once gap 1 is fixed, the worker will call the model, and the token quota will not hold either.
- Until this is fixed, the only kill switches that work across runs are the `ALERT_INGESTION_ENABLED` and `ALERT_AI_ENABLED` settings, which are read when each run starts.

ADR-0031 decision 5 asks for a search cap, kill switches and budget control. Today only the per-run cap meets it for the worker.

**Why it was left.** The fix needs a new table in alerts-db, which means a migration (Hayyun's area), and it changes how the controls work (Azeez's area).

**Suggested fix.**
1. Keep monthly usage and each capability's disabled state in alerts-db.
2. Take a reservation in a transaction that locks the month's row, so the API and the worker share one count.
3. Read the disabled state at the start of each reservation.

[ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) faces the same trade-off for the SFTP import: a process that runs once and exits loses whatever it kept in memory.

## 4. Two runs at once both search

**Fixed 2026-10-20.** `IngestionCoordinator` holds a PostgreSQL session advisory lock (`PostgresIngestionRunLock`) for the whole run. A run that cannot take it ends as `ALREADY_RUNNING` before reserving quota or searching, for scheduled runs, manual runs and replays alike. The text below records the gap as found.

**What happens.**

- A window starts at the watermark less the 5-minute overlap and ends when the run starts.
- [`IngestionCoordinator`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/IngestionCoordinator.java) stops a second run only when its window is exactly the same: `search_run` is unique on `(window_from, window_to)`, with a 15-minute lease.
- Two runs that start at different times have different windows, so both reserve searches and both search.

**Effect.**

- The searches are doubled. Duplicate URLs are still caught, by the URL hash check and the unique constraint on `canonical_url_hash`, so the stored data stays clean.
- [`ProcessingWatermark.advanceTo`](../../services/event-alert-service/src/main/java/bank/alert/domain/ProcessingWatermark.java) refuses to move the watermark backwards. If the run with the later window finishes first, the other run throws `watermark cannot move backwards` at the end and exits with code 1. Its `search_run` row stays `RUNNING`, and nothing runs that window again.

**When it happens.** Rarely:

- EventBridge Scheduler can deliver a schedule twice.
- Someone starts a manual run while a scheduled one is running.

The service README tells operators not to do the second.

**Why it was left.** How runs should exclude each other is the owner's choice.

**Suggested fix.** Hold a Postgres advisory lock, `pg_try_advisory_lock`, for the whole run. If the lock is taken, end the run as `ALREADY_RUNNING`.

## 5. Worker metrics are not exported

**What happens.** `OperationalMetrics` records Micrometer gauges and counters, such as `alert.outbound.reservations` and `alert.outbound.monthly.searches`. No registry sends them anywhere:

- The API process shows them at `/actuator/metrics`, but sends nothing to CloudWatch.
- The worker has no web server and exits within seconds, so its metrics are lost.

**Effect.** ADR-0031's operational impact asks for worker run metrics and an alarm on failed runs. Two things cover this for now:

- One log line per run, for example `worker run status=FAILED … error_code=PROVIDER_NOT_CONFIGURED`. A CloudWatch Logs metric filter can count these lines.
- The exit code. An alarm can watch for worker tasks that stop with a non-zero code.

**Why it was left.** Exporting metrics adds a dependency and needs CloudWatch, which is not built yet. This belongs to EDA-D1-05.

**Suggested fix.** Either:

- add `micrometer-registry-cloudwatch2`, which sends the last values when the context closes and so suits a short-lived worker; or
- use metric filters on the run log line only.

Pair either one with the non-zero-exit alarm listed in the service README's infrastructure needs.
