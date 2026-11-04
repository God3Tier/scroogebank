# A search starts when an agent adds a domain

Owner: Hayyun. Reviewers: Azeez, Neo Jia Wen (review pending). Status: Implemented and tested locally (backend, frontend, `crm` Terraform); not applied to AWS. Last updated: 2026-11-04.
Parent: [custom source domains plan](event-driven-alerts-custom-source-domains_plan.md). Decision: [ADR-0081](../adr/ADR-0081-search-starts-when-an-agent-adds-a-domain.md) (Proposed).
Branch: `task/event-driven-alerts/search-on-add`, from `dev` at `d18607e`.

## Goal and scope

An agent who adds a search domain sees its effect within minutes: one targeted search of that company over the last 14 days, using only the new domain. Not in scope: any change to the four-hour schedule, the relevance filter, or the shared and official domains.

## Design

**API.** `POST /api/alerts/search-sources/{instrumentId}/domains` saves the domain, then asks `SearchOnAddService` for a search. The reply adds `research` (STARTED, COOLDOWN, DAILY_LIMIT, OFF, FAILED) and `researchLookbackDays`. A 15-minute cooldown per company and 20 started searches in 24 hours are counted from `source_search_request` (migration V12). A search that cannot start never fails the add.

**Starting it.** `alert.search-on-add.mode`: `off` (default), `ecs` (ECS RunTask of the worker's task definition with the research variables), `local-process` (a child process, developer machines only).

**Worker.** With `ALERT_WORKER_RESEARCH_INSTRUMENT` and `_DOMAIN` set, `WorkerRunner` calls `IngestionJob.researchCompany`, which searches only an active added domain of that company through `IngestionCoordinator.research` (no shared sites, no watermark move, same run lock, quota and filter), then runs the pipeline.

**Terraform.** `crm`: variable `alert_search_on_add` (default false), the API's settings, and `ecs:RunTask` and `iam:PassRole` for the worker's task definition and two roles. `alert-worker`: no change.

**UI.** After adding, the Search sources card says what happened to the search.

## Tasks

| ID | Task | Owner | Files | Status |
|---|---|---|---|---|
| SA-01 | Migration, request log, cooldown and daily cap | Hayyun | `db/migration/V12`, `research/`, `repository/` | Done |
| SA-02 | Worker research mode | Hayyun | `ingestion/`, `worker/`, `config/` | Done |
| SA-03 | ECS and local-process triggers | Hayyun | `research/` | Done |
| SA-04 | API reply and OpenAPI | Hayyun | `api/`, `docs/api/openapi.yaml` | Done |
| SA-05 | `crm` Terraform | Hayyun | `stacks/crm` | Done |
| SA-06 | Screen message | Hayyun | `frontend/src/components/SearchSources.jsx` | Done |
| SA-07 | Fixture-mode held-companies fix | Hayyun | `service/AgentHeldInstruments.java` | Done |

## Open items and risks

- Not tried in AWS: the task start through ECS, its permissions, and that the service can reach the ECS API (the `crm` stack's NAT gateway is on only while the stack is running).
- Each started search spends Tavily credits (two searches) and, with AI on, model calls.
- The event-alert-service image must be rebuilt and pushed before applying `crm` with `alert_search_on_add = true`.
