# ADR-0081: Searches by hand and on adding a website, at fixed times, with no small cap

- Status: Proposed (2026-11-05; review pending)
- Decision date/time: 2026-11-05 (Asia/Singapore)
- Decision owner(s): Hayyun (proposer and owner); reviewers: Azeez, Neo Jia Wen (owner of ADR-0031 and ADR-0048)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/event-driven-alerts/search-on-add`
- Related issue/PR: TBD
- Supersedes: none; extends [ADR-0079](ADR-0079-agent-added-search-domains-per-company.md) (a website added by an agent is searched "from the next run", now replaced by [ADR-0082](ADR-0082-one-shared-list-of-search-websites.md)), and changes the schedule and the per-run search caps of [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md) and [ADR-0051](ADR-0051-single-issuer-search-and-relevance-gate.md)
- Superseded by: N/A

## Context

A scheduled run looks only at the last four hours, so one company seldom has a matching article in that window, and an agent who has just added a website sees nothing for hours. A tester or a demo cannot show that a website, or the pipeline, works. The schedule is also "every four hours from whenever it was created", so nobody can say when the next run is. And a run searches at most four queries, two companies, so a company can wait a day and a half for its turn.

## Decision Drivers

- An agent should see the effect of adding a website, or of pressing a button, within minutes.
- Everyone should be able to see when the next automatic search is, and a manual search must not disturb it.
- Every search spends Tavily credits and, with AI on, model calls, so it must be traceable and the monthly budget must still hold.
- Adding a website must keep working when the search cannot start.
- The relevance filter must not change.

## Options Considered

1. **Leave it to the schedule.** No cost, but the effect is invisible for hours.
2. **A shorter schedule.** Rejected in ADR-0080: it spends credits all day.
3. **The API starts a targeted worker task on request.** Chosen.
4. **The API runs the search itself.** Rejected: the Tavily key and model access are held by the worker only.

## Decision

1. **Two ways to start a search.**
   - *Adding a website* starts a search of the companies the agent's clients hold, using only the new website.
   - *Search now* starts a search of one company, or of every company the caller may search (an agent's own; an admin's any held company), using every website: each company's official sites, the shared news sites and every added website.
   - Both look back 7 days (14 until 2026-11-07; news older than 7 days is stale).
2. **Same rules as a scheduled run.** Same image, task definition, roles, network, outbound controls, run lock and strict relevance gate (a title naming the company and an event word). The run stores sources and runs the pipeline. Like a replay it does not move the scheduler watermark, and a manual search never changes the automatic schedule.
3. **The worker re-checks.** A search using one website runs only if that website is an active added website.
4. **Limits.**
   - A 15-minute cooldown per company: a company searched this way recently is skipped, and the page says when it can be searched again.
   - There is no daily cap by default (`alert.search-on-add.daily-cap`, 0). It can be set.
   - The two small per-run caps are removed: `max-queries-per-run` and `max-searches-per-run` now default to 200 instead of 4, so a scheduled run searches every eligible company each time instead of rotating two at a time.
   - The monthly Tavily quota (ADR-0050, default 1,000) stays as the safety net and stops all searching if it would be exceeded.
   - Which companies a scheduled run searches is still decided by the common-holding and concentration rules (ADR-0051); they can be relaxed with their settings.
   - Each company searched is recorded in `source_search_request` with its outcome, who asked and when.
5. **Adding never fails because of the search.** The website is saved first. The reply says what happened to the search, and a search that could not start only means the next automatic run will pick it up.
6. **How it is started** is a setting, `alert.search-on-add.mode`: `off` (the default), `ecs` (run the worker task through the ECS API) or `local-process` (a child process, for a developer's machine only; the command comes from configuration, never from a request).
7. **Fixed automatic times.** The schedule in `stacks/alert-worker` becomes `cron(0 0/4 * * ? *)` in Asia/Singapore: 00:00, 04:00, 08:00, 12:00, 16:00 and 20:00. `GET /api/alerts/search-schedule` returns the previous and next time, computed from `alert.schedule` (interval and zone, default 4 hours and Asia/Singapore), which must match the Terraform schedule. The Event Alerts page shows the next time and a countdown.
8. **Terraform.** `alert-worker`: the schedule above. `crm`: a variable `alert_search_on_add` (default `false`); when true it passes the mode, cluster, the worker's task definition family, subnets and security group to the alerts service and gives its task role `ecs:RunTask` on that task definition family in this cluster and `iam:PassRole` on the worker's two roles, only to `ecs-tasks.amazonaws.com`.
9. **A related fix.** The list of companies an agent holds was wrong: a client with recorded holdings but no alert yet showed nothing. It now matches the recorded positions to their accounts and asks the CRM who owns each account now (active investment accounts only), so a reassignment moves the companies with the client. In fixture mode it reads the bundled holdings file. This is `HoldingsOverview`.

## Consequences

- An agent, or an admin giving a demo, sees results within minutes, and the page always says when the next automatic search is.
- Searching every company each run costs about two Tavily searches per company per run. With a small group this is small; with many companies it will reach the monthly quota in days, which then stops searching until the next month. Size the quota to the plan, or relax the limits above deliberately.
- The first run after the schedule changes comes at the next fixed time.
- The API's task role can start one task definition and nothing else.
- Two searches close together run two tasks; the worker's run lock makes the second wait or end as ALREADY_RUNNING.
- A 7-day window can return articles from before a website was added. That is intended.
- The effect needs the worker stack applied with ingestion on. With ingestion off the worker ends as DISABLED.
- The alerts service shows the schedule from its own settings and cannot see whether the schedule is switched on in AWS. Keep `alert.schedule` in step with the Terraform schedule.

## Security and Privacy Impact

No client, account or holding data goes into the task or to Tavily; only company ids, a public website and a look-back. The website has passed the ADR-0082 policy. The permission is narrow: one task definition, one cluster, two roles, one service. Terms of use of each added website remain the agent's responsibility.

## Cost Impact

No new AWS resources. Fargate task time and Tavily and model calls per search, bounded by the cooldown, the optional daily cap and the monthly quota.

## Operational Impact

New migration `V14` (table `source_search_request`). The event-alert-service image must be rebuilt and pushed, since the API and worker share it. To switch on in AWS: apply `stacks/alert-worker` (new schedule), push the image, then apply the `crm` stack with `alert_search_on_add = true`. It is off until then.

## Validation

Backend, contract and frontend tests pass; `terraform validate` and `terraform test` pass for `crm` and `alert-worker`. On a developer machine against the real Tavily API, adding a website for DBS started the worker, which stored an article that passed the filter, and a second search of DBS inside the cooldown was reported as such. Not verified: starting the task through ECS in AWS, including the permissions and that the service can reach the ECS API, and the cron schedule in AWS.

## References

- [ADR-0079](ADR-0079-agent-added-search-domains-per-company.md), [ADR-0082](ADR-0082-one-shared-list-of-search-websites.md), [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md), [ADR-0048](ADR-0048-alert-worker-terraform-stack.md), [ADR-0049](ADR-0049-alert-source-to-alert-pipeline.md), [ADR-0051](ADR-0051-single-issuer-search-and-relevance-gate.md)
- [Feature plan](../feature-plans/event-driven-alerts-search-on-add_plan.md)
