# Monitoring and alerting

## Client Health Monitor

RH-SF09 exposes only `/actuator/health/liveness` and
`/actuator/health/readiness`. Both are anonymous for ALB/ECS probes and suppress
component details. Liveness has no external dependency. Readiness includes the
application readiness state and health-db connectivity; migrations must succeed
before startup. Bedrock, transaction replay, assignment lookup and SQS/Auditor
do not participate in readiness.

Custom Client Health metrics are:

| Area | Metrics | Bounded tags |
|---|---|---|
| Analysis | `client_health_analysis_runs_total`, `client_health_analysis_duration_seconds`, `client_health_analysis_watermark_age_seconds` | `outcome`, `failure_stage` |
| Replay | `client_health_replay_runs_total`, `client_health_replay_pages_total`, `client_health_replay_transactions_total`, `client_health_replay_rejected_total`, `client_health_replay_duration_seconds` | `outcome` |
| Rules | `client_health_rule_evaluations_total`, `client_health_rule_signals_total` | `rule`, `outcome`, `severity`, `ruleset` |
| Tasks | `client_health_task_outcomes_total`, `client_health_task_transition_total`, `client_health_task_conflict_total`, `client_health_authorization_denied_total` | bounded outcome or transition only |
| Assignment | `client_health_assignment_requests_total`, `client_health_assignment_duration_seconds` | `outcome` |
| AI | `client_health_ai_calls_total`, `client_health_ai_fallback_total`, `client_health_ai_validation_rejected_total`, `client_health_ai_provider_failures_total`, `client_health_ai_latency_seconds`, `client_health_ai_input_tokens_total`, `client_health_ai_output_tokens_total`, `client_health_ai_estimated_cost_usd` | `outcome`, `source`, `reason` |
| Audit | `client_health_audit_events_total`, `client_health_audit_backlog`, `client_health_audit_oldest_age_seconds` | `outcome`, `failure` |

The watermark-age gauge calculates the current age from the last successful
analysis watermark whenever it is sampled. Failed runs do not reset or freeze
that age. AI call counters record each actual Bedrock attempt once under its
own outcome; validation rejected before the provider call records no Bedrock
attempt.

Spring supplies `http.server.requests`; no custom URI or entity labels are
added. Metrics must never contain client, account, task, transaction, finding,
Agent or correlation identifiers, raw URLs, exception messages, prompt/model
content or other free-form values. New tag values require code review as a
bounded enum.

RH-SF11E publishes the API service's and scheduled task's metrics every minute
to `Scrooge/ClientHealth`. Their task roles may call only
`cloudwatch:PutMetricData` in that namespace. The shared CRM dashboard adds
analysis freshness/duration, task outcomes, audit backlog, assignment failures,
authorization denials and AI safety outcomes.

SNS alarms notify the existing CRM alarm topic, with Garvit as primary and
Swayam as backup:

- any failed analysis run, plus the existing non-zero ECS task-exit event;
- successful-analysis watermark age above 27 hours;
- more than 100 pending Client Health audit events for 15 minutes, or an oldest
  event age above 15 minutes;
- at least three unavailable assignment lookups in a 15-minute window;
- more than ten authorization denials in five minutes; and
- any AI validation rejection or provider failure. Expected
  `AI_DISABLED` template fallbacks do not alarm.

Missing custom metrics are non-breaching so a stopped or not-yet-initialized
stack does not page operators. RH-SF12 must calibrate these project defaults
against synthetic lower-environment load before production/demo enablement and
record an SNS delivery drill. No static AWS credential is used.
