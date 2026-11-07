# ADR-0049: Source-to-alert pipeline, operator replay and worker metrics

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-10-20 (Asia/Singapore)
- Decision owner(s): Hayyun (proposer). Azeez reviews as owner of search, AI and the scheduled worker; Neo Jia Wen reviews the matching and data-boundary parts (ADR-0031, ADR-0032)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/event-driven-alerts`
- Related issue/PR: TBD
- Supersedes: N/A. Implements [ADR-0032](ADR-0032-xf1-instrument-master-deterministic-matching.md) decision 4 and answers gaps 1, 2, 5 and 6 in [the alert gaps](../feature-plans/event-driven-alerts_gaps.md)
- Superseded by: [ADR-0066](ADR-0066-crm-linked-current-alert-holdings.md) in part (alerts route to the client's current agent from the CRM, and holdings are read through the CRM-linked contract)

> **Provider amendment (2026-10-23):** ADR-0050 supersedes decision 6 for the
> active deployment path. The inactive AgentCore runtime adapter was removed to
> avoid maintaining a path that the organization SCP blocks;
> `TavilySearchProvider` is the sole live discovery adapter.
> Decisions 1–5 and 7–8 are provider-neutral and unchanged.

## Context

A worker run searched and stored results but never turned them into alerts. Four things were missing: the article text, a way to say which instruments an article is about, severity and relevance values, and a rule for which agent gets which alert. There was also no search client, no way for an operator to replay a past window, and no worker metrics that survive a process that lives for seconds.

The pieces that already existed are sound and are reused unchanged: the AI classifier and briefing generator with their safety checks, per-agent holdings reads, exposure and materiality scoring, the per-client alert uniqueness key, the audit outbox, and the run lock and persisted quotas of [ADR-0014](ADR-0014-alert-observability-cost-and-kill-switches.md).

## Decision Drivers

- Reproducible, explainable alerts: the same input gives the same alert and shows why ([ADR-0032](ADR-0032-xf1-instrument-master-deterministic-matching.md)).
- An agent sees only alerts for their own clients; no private holding data leaves Singapore.
- Store the least third-party content that ADR-0011 allows, and expire it.
- Fail closed and never lose a source silently when the model is unavailable.
- No new always-on component, endpoint or message broker.

## Options Considered

- **Fan-out: one alert per client and instrument, assigned to that client's own agent (chosen)** versus one alert per event for a single agent. The first matches the existing `(client, event, instrument)` uniqueness key and agent-scoped reads, and the model writes one shared briefing per event ([ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md), 12 October revision).
- **Excerpt: keep it in memory for the run** versus **store the minimum excerpt on the source row (chosen).** ADR-0011 allows "the minimum excerpt needed for traceability", and a stored excerpt lets a pending source be classified on a later run, after an outage or after the model is switched on, and lets a replay repeat the work. In-memory would lose both.
- **Severity: ask the model** versus **a fixed table by event type and direction (chosen).** The table is reproducible and the model never sets its own materiality.
- **Replay: an authenticated HTTP endpoint on the API** versus **a one-off worker task with override variables (chosen).** The API would need permission to start ECS tasks, and a new endpoint is new attack surface. A task start is already authenticated by AWS (the MFA deploy role) and recorded by CloudTrail.
- **Metrics: scrape or push from a sidecar** versus **export from the process as it exits (chosen).** The worker lives for seconds, so Micrometer's CloudWatch registry publishes its last values when the context closes.

## Decision

1. **Pipeline.** After each worker run (scheduled or replay), `SourcePipeline` takes the oldest stored sources not yet finished, up to `alert.pipeline.max-sources-per-run` (default 50, at most 200), still inside their 90-day retention. For each it:
   1. matches it to the instrument master deterministically (decision 3);
   2. classifies it once through the existing safe classifier;
   3. writes one shared briefing;
   4. finds every agent whose clients hold an affected instrument;
   5. creates the alerts through the existing generation service, once per agent, which applies exposure, materiality, the per-client uniqueness key and the daily limit of ten per agent.
2. **Stored excerpt and processing state.** `source_result` gains `excerpt` (at most 1,000 characters, the provider's snippet with control characters removed), `processed_at` and `processing_outcome` (migration V4). The excerpt expires with the row and is redacted by the retention job. Without a snippet the title is the excerpt. A source is finished only on a definitive outcome: `NO_MATCH`, `NO_HOLDERS`, `NO_ALERTS`, `ALERTS_CREATED`, or a model refusal (`ABSTAINED`, `RULE_REVIEW`).
3. **Matching.** An exact, case-sensitive ticker token, a named issuer, or a named sector, after lower-casing and removing punctuation. Ticker and issuer together score relevance 1.00, either alone 0.80, sector only 0.40. Inactive instruments never match, and an unnamed instrument is never guessed. The same text and master always give the same matches (`matcher-v1`).
4. **Severity.** A fixed table: regulatory and credit 0.80, corporate action 0.70, earnings and leadership 0.60, macro 0.50, other 0.30, plus 0.10 for a negative event, capped at 1.00 (`severity-v1`).
5. **Gating and deferral.** The pipeline does nothing while the model is switched off. If the model is unavailable for now (disabled, over quota or budget, or a provider failure), the source stays pending and the pass stops, so the next run tries again. One source failing is counted and the pass moves on; an erroring source exits the worker with code 1 so the existing failed-run alarm fires. A deferral is not a failure.
6. **Historical AgentCore client (superseded and removed).** The original implementation signed MCP calls for the Tokyo Gateway and was tested only against a fake gateway. ADR-0050 replaced it with Tavily after the organization SCP blocked live invocation; inactive runtime code and environment variables are no longer retained.
7. **Operator replay.** An operator starts a one-off worker task with `ALERT_WORKER_REPLAY_FROM`, `_TO`, `_OPERATOR` and `_CORRELATION_ID`. All four are required: a half-filled request is refused. The worker writes an `alert.replay-requested` audit event first and refuses if the outbox is full or unavailable, then replays that window through the existing bounded replay, which never moves the scheduler watermark. AWS authenticates who may start the task and CloudTrail records it; the `replay_command` output of the `alert-worker` stack is the command.
8. **Metrics.** `micrometer-registry-cloudwatch2` exports the service's metrics, plus new `alert.worker.*` counters, to the `CRM/EventAlerts` namespace, **off by default** (`ALERT_CLOUDWATCH_METRICS_ENABLED`). The worker refreshes the shared monthly usage gauges before exit. The `alert-worker` stack grants `cloudwatch:PutMetricData` for that one namespace only when the switch is on.

## Rationale

Every step is either deterministic or already built and tested, and the only new probabilistic step is the classifier that ADR-0010 already governs. Keeping state in the database makes the pipeline restartable and a replay repeatable without a queue. Putting the operator's identity and the audit write before any search means a replay can never run unaudited.

## Consequences

### Positive

- A worker run now ends in alerts for the right agents, with each agent seeing only their own clients' alerts.
- A model outage or an exhausted quota delays alerts instead of losing sources.
- No new infrastructure beyond one IAM statement and one optional environment variable.

### Negative / Trade-offs

- The stored excerpt is third-party text kept for up to 90 days (within ADR-0011), and a source row is slightly larger.
- Deterministic matching misses news that names no ticker, issuer or sector (accepted in ADR-0032).
- A source whose reason code is not in the deferred list is finished on its first refusal and is not retried; adding a code to the list needs a code change.
- CloudWatch custom metrics cost about USD 0.30 a month for each distinct metric and tag combination, so enabling them should be a decision.
- A replay's authentication is AWS's, not the application's: anyone allowed to run the task and set its variables can replay.

## Security & Privacy Impact

- No client or holding data goes into any search or model request: matching uses only public text and the public instrument master, and fan-out reads holdings inside the service.
- The gateway call uses short-lived task-role credentials; no key is stored.
- The excerpt is bounded, stripped of control characters, treated as untrusted text by the classifier, and redacted at expiry.
- A replay records who asked for it and why before it searches.

## Cost Impact

Each pass is bounded by `max-sources-per-run` and by the existing model-token quota and USD 25 monthly stop, which the classifier already reserves against. No new AWS resource is added; CloudWatch metrics are the only new billable item and are off by default.

## Operational Impact

- New log line per run: `worker pipeline examined=… processed=… no_match=… alerts_created=… deferred=… errors=… skipped=…`. Counts only.
- To replay: use the `replay_command` output, fill the four values, run it, then read the log group.
- Sources the model could not handle stay pending until the model is on and within quota; nothing needs clearing.

## Validation

- Unit tests: matcher (ticker token rules, normalisation, sector-only, determinism, inactive instruments), severity table, pipeline (skip when the model is off, no-match, deferral stops the pass, one error does not stop it), replay validation, the worker runner, and the bounded Tavily request/response/error contract.
- PostgreSQL tests: one source becomes one alert for each of two agents, each visible only to its own agent; a rerun creates no duplicates; a deferred source is processed after recovery; an expired source is ignored; a replay writes its audit event first and leaves the watermark alone.
- Not yet shown: a real Tavily result flowing through the classifier and alert pipeline. It needs the live smoke test in the [event-alert operations runbook](../runbooks/event-driven-alerts-operations.md).

## References

- [ADR-0010](ADR-0010-alert-ai-provider-and-safety.md), [ADR-0011](ADR-0011-alert-news-content-governance.md), [ADR-0014](ADR-0014-alert-observability-cost-and-kill-switches.md), [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md), [ADR-0032](ADR-0032-xf1-instrument-master-deterministic-matching.md), [ADR-0048](ADR-0048-alert-worker-terraform-stack.md)
- [Alert gaps](../feature-plans/event-driven-alerts_gaps.md)
- [AgentCore search spike](../architecture/event-driven-alerts-search-spike.md)

## Revision history

- 2026-10-20: proposed.
