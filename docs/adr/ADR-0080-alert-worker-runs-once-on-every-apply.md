# ADR-0080: The alert worker runs once at the end of every apply

- Status: Proposed (2026-11-04; review pending)
- Decision date/time: 2026-11-04 (Asia/Singapore)
- Decision owner(s): Hayyun (proposer and owner); reviewers: Azeez, Neo Jia Wen (owner of ADR-0031 and ADR-0048)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/event-driven-alerts/run-worker-on-apply`
- Related issue/PR: TBD
- Supersedes: none; extends [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md) decision 1 (a run every four hours) and [ADR-0048](ADR-0048-alert-worker-terraform-stack.md) (the worker stack). The four-hour schedule is unchanged.
- Superseded by: N/A

## Context

The worker is started by an EventBridge Scheduler schedule, `rate(4 hours)`. A rate schedule makes its first call one interval after it is created or switched on. Every deployment starts from an empty account (ADR-0058), so after each deploy, and after each apply that switches ingestion on, the Event Alerts page stays empty for up to four hours. A tester or demo has to start a run by hand with the `run_once_command` output.

## Decision Drivers

- A deployment should show alerts soon after it is up, without a manual step that is easy to forget.
- Runs spend Tavily credits (the free plan is limited) and, with AI on, Bedrock calls.
- The worker's behaviour, and the schedule, should not change.

## Options Considered

1. **Leave it manual.** No change and no extra cost, but the manual step is skipped or forgotten.
2. **A shorter schedule** such as every few minutes. Rejected: it spends credits all day for the sake of the first run.
3. **A run at the end of every apply, then the four-hour schedule as before.** Chosen.
4. **A one-time EventBridge schedule a few minutes after apply.** Needs a computed time and changes the plan on every apply. Rejected.

## Decision

1. The `alert-worker` stack gets a `terraform_data.run_on_apply` resource. Its `local-exec` runs `aws ecs run-task` for the worker's task definition, in the CRM cluster, private subnets and alerts security group, exactly as the existing `run_once_command` does.
2. It runs on every apply (`triggers_replace = timestamp()`), and only while `schedule_on` is true: the CRM stack is running and `ingestion_enabled` is true. With ingestion off the worker would exit at once as DISABLED, so nothing is started.
3. A new variable `run_on_apply` (default `true`) switches it off.
4. A failed start only warns (`on_failure = continue`); the apply and the schedule are not affected.
5. No application code, image or Flyway migration changes. The schedule, its four-hour rate and its Singapore time zone are unchanged.

## Consequences

- After an apply with ingestion on, alerts can appear within minutes instead of hours, if holdings exist and news passes the relevance filter.
- Every apply spends one run's Tavily credits. Applies that change nothing else still start a run, and a plan always shows `run_on_apply` as a change. Use `run_on_apply = false` for applies that should not search.
- The worker's run lock (`IngestionRunLock`) keeps this run and a scheduled run from searching at once; the later one ends as ALREADY_RUNNING. Runs do not share a window, so a repeat run searches again from the watermark.
- On a fresh deployment the database has no clients yet, so the run has nothing to search. It helps most when an apply happens against existing data, or after holdings have been recorded.
- The machine that applies needs the AWS CLI and credentials for the `crm` role, as the runbook already requires.

## Security and Privacy Impact

None new. The run is the same task, role and network as a scheduled run, started with credentials the applier already uses for the apply. No client, account or holding data is added to the command.

## Cost Impact

One extra worker run per apply: a small Fargate task plus, with ingestion on, the Tavily searches for each held company (two per company) and, with AI on, model calls for new articles. No new AWS resources.

## Operational Impact

A `terraform_data` resource is added to the stack and appears in each plan. The runbooks note the new behaviour and the off switch. The failed-run alarm still reports a run that exits non-zero.

## Validation

`terraform validate` and `terraform test` (11 runs, three new) pass against the mocked provider: one run when ingestion is on, none with `run_on_apply = false`, none with ingestion off. Not verified: the command against real AWS, including from a Windows shell.

## References

- [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md), [ADR-0048](ADR-0048-alert-worker-terraform-stack.md), [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md)
- [Event alerts operations runbook](../runbooks/event-driven-alerts-operations.md), [Deploy to AWS](../runbooks/deploy-aws.md)
