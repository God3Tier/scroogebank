# Searches by hand, on adding a website, and at fixed times

Owner: Hayyun. Reviewers: Azeez, Neo Jia Wen (review pending). Status: Implemented and tested locally (backend, frontend, `crm` and `alert-worker` Terraform); not applied to AWS. Last updated: 2026-11-05.
Parent: [event-driven alerts plan](event-driven-alerts_plan.md); builds on the [custom source domains plan](event-driven-alerts-custom-source-domains_plan.md), whose per-company websites ADR-0082 replaces.
Decisions: [ADR-0081](../adr/ADR-0081-search-starts-when-an-agent-adds-a-domain.md) and [ADR-0082](../adr/ADR-0082-one-shared-list-of-search-websites.md) (both Proposed).
Branch: `task/event-driven-alerts/search-on-add`, from `dev` at `d18607e`.

## Goal and scope

Agents and admins see what is searched and when, can search by hand, and an agent who adds a website sees its effect in minutes. One shared list of websites replaces the per-company ones. Not in scope: any change to the relevance filter, the shared and official sites, or the AI step.

## Design

**Websites.** `search_website` (migration V15) holds the websites agents add, searched for every company. Any agent adds, any agent or admin removes, at most 20. The per-company table is kept as history and no longer read.

**Searches.** `SearchOnAddService` starts one worker task for the companies concerned: after adding a website, the agent's companies using only that website; for Search now, one company or all the caller may search, using every website. 14-day look-back, a 15-minute cooldown per company that the page reports, no daily cap by default, the monthly quota as the safety net, and the two per-run caps raised from 4 to 200. A failed start never fails the add. Each company searched is recorded in `source_search_request` (V14).

**Worker.** `ALERT_WORKER_RESEARCH_INSTRUMENTS` (and `_DOMAIN`) make `WorkerRunner` call `IngestionJob.researchCompanies`, which uses `IngestionCoordinator.research` (no watermark move, same run lock, quota and filter) and then runs the pipeline.

**Who holds what.** `HoldingsOverview` matches recorded positions to accounts and asks the CRM who owns each (fixture mode: the bundled holdings file). `GET /api/alerts/search-holdings` gives client ids, share of portfolio, quantity and value; the page looks the names up in the CRM.

**Schedule.** `stacks/alert-worker` runs at `cron(0 0/4 * * ? *)` Singapore time. `GET /api/alerts/search-schedule` gives the next time from `alert.schedule`; the page shows it with a countdown. A manual search never changes it.

**Terraform.** `crm`: `alert_search_on_add` (default false), the API's settings and `ecs:RunTask` and `iam:PassRole` for the worker. `alert-worker`: the schedule.

**UI.** Event Alerts: briefings, the client alerts (always open), then Search sources: next automatic search, the shared websites with add and remove, Search now for all companies, and each company with its official sites, the clients that hold it and a Search now button.

## Tasks

| ID | Task | Owner | Status |
|---|---|---|---|
| SA-01 | Request log and cooldown (V14), `SearchOnAddService` | Hayyun | Done |
| SA-02 | Worker research mode for several companies | Hayyun | Done |
| SA-03 | ECS and local-process triggers | Hayyun | Done |
| SA-04 | Shared websites (V15), service, API and OpenAPI | Hayyun | Done |
| SA-05 | Holdings overview and its API | Hayyun | Done |
| SA-06 | Search now API | Hayyun | Done |
| SA-07 | Fixed schedule, schedule API | Hayyun | Done |
| SA-08 | `crm` and `alert-worker` Terraform | Hayyun | Done |
| SA-09 | Search sources screen, client alerts always open | Hayyun | Done |

## Open items and risks

- Not tried in AWS: the task start through ECS and its permissions, that the service can reach the ECS API, and the cron schedule.
- Searching every company each run costs about two Tavily searches per company per run; size the monthly quota to the plan.
- `alert.schedule` on the alerts service must match the Terraform schedule.
- The owning agent's name shown to admins depends on the user service returning each agent's login id.
- The event-alert-service image must be rebuilt and pushed before the `crm` apply.
