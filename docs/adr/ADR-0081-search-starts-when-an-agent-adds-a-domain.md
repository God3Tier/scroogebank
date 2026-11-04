# ADR-0081: A search starts when an agent adds a domain

- Status: Proposed (2026-11-04; review pending)
- Decision date/time: 2026-11-04 (Asia/Singapore)
- Decision owner(s): Hayyun (proposer and owner); reviewers: Azeez, Neo Jia Wen (owner of ADR-0031 and ADR-0048)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/event-driven-alerts/search-on-add`
- Related issue/PR: TBD
- Supersedes: none; extends [ADR-0079](ADR-0079-agent-added-search-domains-per-company.md) (a site added by an agent is searched "from the next run") and relies on [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md) and [ADR-0048](ADR-0048-alert-worker-terraform-stack.md). The four-hour schedule is unchanged.
- Superseded by: N/A

## Context

ADR-0079 lets an agent add a search domain for a company, and says it is searched "from the next run". A scheduled run looks only at the last four hours, so one company seldom has a matching article in that window, and an agent who has just added a site sees nothing for hours. A tester or demo cannot show that adding a site changes what is found.

## Decision Drivers

- An agent adding a site should see its effect soon, without waiting for the schedule.
- Every search spends Tavily credits and, with AI on, model calls, so it must be limited and traceable.
- Adding a site must keep working when the search cannot start.
- Nothing should change in the scheduled run or in the relevance filter.

## Options Considered

1. **Leave it to the next scheduled run.** No cost, but the effect is invisible for hours.
2. **A shorter schedule.** Rejected in ADR-0080: it spends credits all day.
3. **The API starts one targeted worker task when a site is added.** Chosen.
4. **The API runs the search itself.** Rejected: the Tavily key and model access are held by the worker only, and the API should not gain them.

## Decision

1. **What starts.** After a domain is saved and committed, the API asks for one worker run in a new "research" mode: that company only, over its last 14 days, using only the new domain. The shared news sites and the company's official sites are not searched by this run.
2. **The same rules apply.** It uses the same image, task definition, roles, network, outbound quota, run lock and strict relevance gate as a scheduled run (a title naming the company and an event word). It stores sources and runs the same pipeline. Like a replay it does not move the scheduler watermark, and the scheduled runs carry on at their usual times; there is no timer to reset.
3. **The worker re-checks.** It searches only when the domain is an active added domain of that company, so a request cannot make it search anything else.
4. **Limits.**
   - A cooldown of 15 minutes per company: a company searched this way recently is not searched again.
   - A cap of 20 started searches in any 24 hours, across all companies.
   - The monthly Tavily quota (ADR-0050) still applies on top.
   - Each request is recorded in `source_search_request` with its outcome (STARTED, COOLDOWN, DAILY_LIMIT, FAILED), who asked and when.
5. **Adding never fails because of the search.** The domain is saved first. The reply carries `research` (STARTED, COOLDOWN, DAILY_LIMIT, OFF or FAILED) and, when started, `researchLookbackDays`. The page tells the agent what happened.
6. **How it is started** is a setting, `alert.search-on-add.mode`: `off` (the default), `ecs` (run the worker task through the ECS API) or `local-process` (run the worker as a child process, for a developer's machine only; the command comes from configuration, never from a request).
7. **Terraform.** The `crm` stack gets a variable `alert_search_on_add` (default `false`). When true it passes the mode, cluster, the worker's task definition family, subnets and security group to the alerts service, and gives that service's task role `ecs:RunTask` on that task definition family in this cluster and `iam:PassRole` on the worker's two roles, only to `ecs-tasks.amazonaws.com`. The `alert-worker` stack does not change.
8. **A related fix.** In fixture holdings mode the list of companies an agent holds was read from a table that is empty until alerts are scored. It now reads the bundled holdings file, as the alerts do, with a reassignment in `client_assignment` taking precedence.

## Consequences

- An agent sees the effect of a new site within minutes, and a demo can show it.
- Each started search costs two Tavily searches (one per topic) plus model calls for new articles. At most 20 start per day, so at most 40 Tavily searches from this feature per day.
- The API's task role can now start one task definition. It cannot start anything else.
- Two adds for different companies close together run two tasks; the worker's run lock makes the second wait or end as ALREADY_RUNNING rather than search twice at once.
- The 14-day window can return articles from before the site was added. That is intended: it is how a new site shows results at once.
- The effect needs the worker stack applied with ingestion on. With ingestion off the worker ends as DISABLED and the agent sees STARTED while nothing is found.

## Security and Privacy Impact

No client, account or holding data goes into the task or to Tavily; only a company id, a public domain and a look-back. The domain has already passed the ADR-0079 policy. The permission is narrow: one task definition, one cluster, two roles, one service. Terms of use of each added site remain the agent's responsibility (ADR-0079).

## Cost Impact

No new AWS resources. Fargate task time and Tavily and model calls per started search, bounded by the cooldown, the daily cap and the monthly quota.

## Operational Impact

New migration `V12` (table `source_search_request`). The event-alert-service image must be rebuilt and pushed, since the API and worker share it. To switch on in AWS: apply the `crm` stack with `alert_search_on_add = true` after the image is pushed, with `stacks/alert-worker` applied and ingestion on. It is off until then.

## Validation

Backend unit and integration tests, OpenAPI contract tests and frontend tests pass. `terraform validate` and `terraform test` pass for the `crm` stack. On a developer machine, with the real Tavily API: adding `businesstimes.com.sg` for DBS started the worker, which stored one article that passed the filter, and a second add for DBS inside the cooldown was reported as COOLDOWN. Not verified: starting the task through ECS in AWS, including the permissions and that the service can reach the ECS API.

## References

- [ADR-0079](ADR-0079-agent-added-search-domains-per-company.md), [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md), [ADR-0048](ADR-0048-alert-worker-terraform-stack.md), [ADR-0049](ADR-0049-alert-source-to-alert-pipeline.md)
- [Feature plan](../feature-plans/event-driven-alerts-search-on-add_plan.md)
