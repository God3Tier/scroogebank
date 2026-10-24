# Business Dashboard (x-factor 2) — Feature Plan

- Feature name: `business-dashboard`
- Shared feature branch: `feat/business-dashboard`, restarted from `dev` at `5f9690a` on 2026-10-09. The first line is kept as `backup/business-dashboard-v1`.
- Subtask branch prefix: `task/business-dashboard/<subtask>`
- Status: **In progress.** Service, audit, roster and aggregate endpoints are built and unit-tested, and the service runs in AWS from its own stack (BD-T11); end-to-end checks and UI are not done.
- Primary developer: **TBC** (must be named before tasks are confirmed; see §20)
- Plan last updated: 2026-10-20 (deployment: BD-T11)
- Decisions: [ADR-0033](../adr/ADR-0033-xf2-dashboard-topology-cache-audit.md) (topology, cache, audit), [ADR-0038](../adr/ADR-0038-dashboard-metric-definitions.md) (metric definitions), [ADR-0039](../adr/ADR-0039-dashboard-aggregate-endpoints.md) (aggregate endpoints), [ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md) (its own Terraform stack). ADR-0033 and ADR-0038 are Accepted; ADR-0039 and ADR-0047 are Proposed.
- Contracts: [`docs/api/dashboard-api.yaml`](../api/dashboard-api.yaml) (what the dashboard serves), [`docs/api/dashboard-aggregates.yaml`](../api/dashboard-aggregates.yaml) (what the owning services serve it).

## 0. Where things stand (2026-10-09)

| Part | State |
|---|---|
| `services/dashboard-service` (Java 25, Spring Boot, no database) | Five endpoints, panel degradation, 3-minute per-caller cache, Cognito access-token check for the CRM app client, `DASHBOARD_VIEW` audit to SQS, adapters for all four upstreams, per-agent table, startup warm-up of the upstream connections. 105 tests pass |
| Auditor | Accepts `source_service: DASHBOARD` with `event_type: DASHBOARD_VIEW` and checks the payload |
| client-service | `GET /aggregates/clients/stats`, `/aggregates/clients/action-items` and `/aggregates/clients/team-comparison`; index on `clients(created_at)` |
| account-service | `GET /aggregates/accounts/stats` and `/aggregates/accounts/ids`; index on `accounts(opening_date)` |
| user-service | `GET /aggregates/transactions/stats`, in front of the receiver |
| SFTP receiver | Private `POST /aggregates/transactions/stats`; index on `transactions(transaction_date)` |
| End-to-end checks with real tokens | Not run |
| Deployment | Defined in Terraform as its own stack, `infrastructure/terraform/stacks/business-dashboard` (ADR-0047, 2026-10-20); plan-tested with mocked providers, and applied to the team's AWS account, where dashboard-service runs (confirmed 2026-10-20) |
| UI | Not possible yet: there is no `frontend/` on `dev`. `task/frontend-building` holds one of the three mockup commits (`851f737`); `task/business-dashboard/frontend-mockup` holds all three on an old base and must not be merged |

What changed on `dev` since the first branch, and how this plan follows it:

- **Accounts moved to account-service** with `owner_agent_id` (ADR-0035), so account figures are scoped by owner rather than joined to clients.
- **Deletes are soft** (ADR-0036): deleted clients and accounts are left out of every figure.
- **Transactions have their own database and an `account_id`** (ADR-0037), so agent transaction figures are scoped by account. The receiver plays transaction-service's query role.
- **Audit goes to SQS** (ADR-0004 on the feature line, ADR-0020): the dashboard sends `DASHBOARD_VIEW` events straight to the queue; the log-only emitter is gone.
- **`GET /api/users` is paged and returns `idpSub`**, which joins a user to the figures. The branch's own roster endpoint was dropped.

## 1. Problem statement

The CRM stores what management questions need and answers none of them. An admin cannot see how many clients each agent onboarded this month, how many are still unverified, or how many transactions failed. An agent cannot see the size and make-up of their own book, or which clients are stuck unverified.

## 2. User and business value

- **Admins:** a per-agent view of onboarding, verification and booked deposits, and bank-wide transaction health.
- **Agents:** their own book at a glance, and a work queue of clients to follow up.
- **Course:** a distinct x-factor with no AI that exercises cross-service read composition, aggregation in the owning service, partial-failure degradation and role-scoped data.

## 3. Scope

1. `services/dashboard-service`, composing role-shaped responses from the owning services (ADR-0033).
2. Read-only `/aggregates` endpoints on client-service, account-service, user-service and the receiver (ADR-0039).
3. `DASHBOARD_VIEW` audit events and the auditor's support for them.
4. Tests, contracts and docs. Deployment and UI when their bases exist (§16).

## 4. Explicit non-scope

- No database, no copy of client, account or transaction rows, no write path.
- No AI, forecasting, scoring or composite agent rating (ADR-0038).
- The figures ADR-0038 leaves out of the first cut: ingestion freshness, clients without an account, time-to-verify, and rejected or pending verifications.
- No CSV or PDF export.

## 5. Acceptance criteria

1. An admin sees bank-wide figures, a per-agent table, portfolio breakdowns and transaction health.
2. An agent sees figures for their own clients, accounts and matched transactions only, and a work queue.
3. **An agent cannot obtain another agent's figures by any parameter**, proven by tests in each service.
4. With one upstream down, the other panels still show and the response is 200; only when every panel fails is it 503.
5. Every figure matches its definition in ADR-0038 and is computed by one query in the owning service.
6. Every view, including cache hits, sends a `DASHBOARD_VIEW` event the auditor accepts.
7. No new billable resource beyond one ECS service.

## 6. Architecture

Stateless fan-out (ADR-0033): dashboard-service forwards the caller's token to the owning services over Service Connect, and each scopes its own answer.

| Panel data | From | Scope |
|---|---|---|
| Agent roster | user-service `GET /api/users` (admin-only, paged) | Admins only |
| Client figures, work queue | client-service `/aggregates/clients/*` | `assigned_agent_id` = caller (ADR-0060) |
| Account figures | account-service `/aggregates/accounts/stats` | `owner_agent_id` = caller |
| Transaction figures | user-service `/aggregates/transactions/stats`, which asks account-service for the caller's account IDs and then the receiver | Caller's accounts; `Matched` rows only |

`/aggregates` sits outside `/api`, so API Gateway never routes it, and the owning services do not audit it; the dashboard audits each view (ADR-0039).

## 7. Functional requirements

| Endpoint | Panels | Who |
|---|---|---|
| `/api/dashboard/overview` | `clients`, `accounts`, `transactions`; `agents` (total and enabled) for admins; `teamComparison` (own clients onboarded against the team median, last 7, 30 or 90 days only) for agents | Both |
| `/api/dashboard/portfolio` | `clients`, `accounts`, `accountsPerClient` | Both |
| `/api/dashboard/transactions` | `transactions` | Both |
| `/api/dashboard/action-items` | `clients` (stale unverified, oldest 50 and total), `transactions` (pending backlog) | Both |
| `/api/dashboard/agents` | `agents`: the per-agent table, sorted by one explicit figure | Admins only |

Shared: window of 7, 30 or 90 days or explicit dates, at most 365 days, in Singapore days; every response carries `generatedAt` and per-panel status.

## 8. Relevant non-functional requirements

- **Performance:** upstream calls run in parallel, 10 s each and 15 s in total (raised from 2 s and 3 s, then from 5 s and 8 s, ADR-0033), under API Gateway's 29 s integration timeout; aggregation happens in Postgres on indexed columns.
- **Availability:** one failing upstream costs one panel. No retries. A response with a timed-out or failed panel is not cached, so the next refresh asks again rather than serving the failure for 3 minutes.
- **Consistency:** up to 3 minutes stale, shown by `generatedAt`.

## 9. APIs, schema and jobs

- **APIs:** as §7 and the two contracts above. All `GET` except the receiver's private `POST`.
- **Schema:** three indexes, added to each service's V1 SQL (nothing is deployed): `clients(created_at)`, `accounts(opening_date)`, `transactions(transaction_date)`. No new tables or columns.
- **Jobs:** none.

## 10. Authorization and access

- dashboard-service accepts only Cognito access tokens issued to the CRM app client, like user-service.
- Every `/aggregates` route except the receiver's checks the token and requires the `ADMIN` or `AGENT` group. Per-agent figures are admin-only (403 otherwise). No endpoint takes an agent ID.
- The receiver stays private and unauthenticated, called only by user-service after its check (ADR-0006 decision 5).

## 11. Audit logging

| Event | `event_type` | `action` | Payload |
|---|---|---|---|
| A dashboard view | `DASHBOARD_VIEW` | `VIEW_OVERVIEW`, `VIEW_PORTFOLIO`, `VIEW_TRANSACTIONS`, `VIEW_ACTION_ITEMS`, `VIEW_AGENT_PERFORMANCE` | `scope`, `window_from`, `window_to`, `panels` (statuses), `result_count`, `cache_hit`, `datetime` |

- `source_service` is `DASHBOARD`; `actor_id` is the caller's `sub`; `event_id` is new per view.
- Payloads hold no figures and no client data.
- Best-effort (ADR-0033): a failed send is logged and does not fail the view. With `AUDIT_QUEUE_URL` unset (locally), nothing is sent.

## 12. Error and failure behaviour

| Condition | Behaviour |
|---|---|
| One upstream times out, errors or is not configured | That panel is `UNAVAILABLE` with a reason; HTTP 200 |
| Every panel fails | HTTP 503, every panel `UNAVAILABLE` |
| Upstream refuses the caller | Panel `UNAVAILABLE`, reason `upstream_forbidden` |
| Accounts in the window use two currencies | Account panel `UNAVAILABLE`, reason `mixed_currencies`; transaction value omitted |
| Bad window, sort or `staleDays` | HTTP 400, no upstream call |
| An agent's window is not the last 7, 30 or 90 days | `teamComparison` panel `UNAVAILABLE`, reason `unsupported_window` |
| Agent calls `/api/dashboard/agents` | HTTP 403 |
| Missing or invalid token | HTTP 401 |

## 13. Observability

Panel failures are logged with the panel name and reason (debug for "not configured"); audit send failures are logged with the `event_id`. Fan-out latency and error metrics follow the observability baseline when deployed (ADR-0027).

## 14. Cost impact

One ECS Fargate service, 1 task × 1 vCPU / 2 GB at minimum: roughly USD 45 a month running 24/7 (about USD 0.06 an hour; 0.5 vCPU was too slow to start, ADR-0042 Cost Impact), and USD 0 until infrastructure exists. No database or cache cluster. A few extra SQS messages per view and three indexes in existing databases.

## 15. Security and privacy

- The dashboard holds no credential of its own and reads nothing the caller could not.
- Staff performance data is admin-only, audited, and never turned into a score.
- No personal data is stored; the per-caller cache holds a caller's own figures for 3 minutes.
- Deleted clients and accounts drop out of every figure on the next query.

## 16. Test plan, deployment and rollback

**Tests (all run 2026-10-09; see §17):**

| Where | What |
|---|---|
| dashboard-service | Controller, security, token validator, cache, panel assembly, window (Singapore "today"), SQS emitter, all four adapters against real response JSON, per-agent join and sort, accounts per client, the team comparison panel (preset windows only, small groups' median dropped) |
| auditor | `DASHBOARD` type: valid events of each action pass; wrong event type, action, scope, window, panels, missing actor or datetime are rejected |
| client-service, account-service | Repository tests against Postgres (deleted rows, Singapore day edges, windows, currencies, the team comparison group and median), service and controller tests, router tests that the caller comes from the token only |
| user-service | Controller tests with the real token filter; proxy tests for admin, agent, no accounts and refusals |
| receiver | Repository tests (scope, day edges, unmatched rows, currencies, backlog) and handler tests for every refused request |

**Still to do:** end-to-end with real tokens (agent A sees only A's figures; admin sees all; one service stopped degrades one panel; the auditor stores the view), a seeded dataset with at least three agents whose figures differ, and a performance check once deployed.

**Deployment:** `infrastructure/terraform/stacks/business-dashboard` ([ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md)), applied after the CRM stack ([runbook](../runbooks/deploy-aws.md) step 4). The CRM stack ships the services with their new endpoints (additive, safe without the dashboard); the dashboard stack then adds dashboard-service with Service Connect to user-, client- and account-service, `sqs:SendMessage` on `audit-insert`, and the ALB rule for `/api/dashboard/*` behind API Gateway's shared `/api` route. API Gateway must not route `/aggregates/*`. Configuration is in `services/dashboard-service/README.md`.

**Rollback:** scale the service to zero or destroy the business-dashboard stack; no other service calls it. The agent home page's transactions panel and the Insights pages then show the panel as unavailable. The `/aggregates` endpoints and indexes can stay.

## 17. ADRs

| ADR | Decision | State |
|---|---|---|
| [ADR-0033](../adr/ADR-0033-xf2-dashboard-topology-cache-audit.md) | Stateless fan-out, caller's JWT, 3-minute cache, best-effort audit | Accepted 2026-10-09; owner Neo Jia Wen |
| [ADR-0038](../adr/ADR-0038-dashboard-metric-definitions.md) | What each figure means, and what is left out | Accepted 2026-10-09; owner Neo Jia Wen |
| [ADR-0039](../adr/ADR-0039-dashboard-aggregate-endpoints.md) | Private `/aggregates` routes, token scoping, user-service in front of the receiver | Proposed; Neo Jia Wen and Joseph Yau (agreement pending) |

The first branch's ADR-0001, 0003 and 0004 are replaced by ADR-0033 and were not carried over; its ADR-0002 is rewritten as ADR-0038.

## 18. Task breakdown

Owners marked TBC must be confirmed before more parallel work starts (`project_plan.md` §2.3). Joseph Yau reviews every change to Feature 2 and 3 code.

| Task ID | Task | Primary dev | Collaborators | Files/components | Branch | Dependencies/conflict zones | Status | Last sync |
|---|---|---|---|---|---|---|---|---|
| BD-T01 | ADR-0038, ADR-0039, contracts, this plan | TBC | Neo Jia Wen | `docs/adr/`, `docs/api/dashboard-*.yaml`, this file | `task/business-dashboard/adr` | ADR index | Drafted | 2026-10-09 |
| BD-T02 | Restart branch; carry the service across | TBC | — | `services/dashboard-service/**`, root `pom.xml` | `feat/business-dashboard` | Root `pom.xml` modules | Done | 2026-10-09 |
| BD-T03 | Token check, config, SQS audit emitter | TBC | — | `services/dashboard-service/{security,audit,config}` | `task/business-dashboard/service-restart` | — | Done | 2026-10-09 |
| BD-T04 | Auditor `DASHBOARD` type | TBC | Auditor owner (review) | `services/auditor/src/internal/models/service_type.go`, `src/serverd/queue/payload_unmarshalling.go` | `task/business-dashboard/audit` | Auditor validation | Done; review pending | 2026-10-09 |
| BD-T05 | Paged roster | TBC | — | `services/dashboard-service/upstream/http` | `task/business-dashboard/roster-paging` | — | Done | 2026-10-09 |
| BD-T06 | Client aggregates | TBC | Joseph Yau (review) | `services/client-profile-management-service/src/internal/{models,repository,service,controller}/aggregate*`, router, `data/V1_init.sql` | `task/business-dashboard/client-stats` | Feature 2 router and schema | Done; review pending | 2026-10-09 |
| BD-T07 | Account aggregates | TBC | Joseph Yau (review) | `services/account-service/src/internal/.../aggregate*`, router, `data/V1_init.sql` | `task/business-dashboard/account-stats` | account-service router and schema | Done; review pending | 2026-10-09 |
| BD-T08 | Transaction aggregates | TBC | Joseph Yau (review) | `services/sftp/receiver/src/{internal,serverd}`, `data/init.sql`; `services/user-service/.../TransactionStats*` | `task/business-dashboard/transaction-stats` | Feature 3 receiver; user-service | Done; review pending | 2026-10-09 |
| BD-T09 | Panels, per-agent table, accounts per client | TBC | — | `services/dashboard-service/{service,upstream}` | `task/business-dashboard/panels` | — | Done | 2026-10-09 |
| BD-T10 | End-to-end checks and seed data | TBC | Joseph Yau | all five services | `task/business-dashboard/e2e` | Needs Cognito users for one admin and two agents | Planned | 2026-10-09 |
| BD-T11 | Deployment: its own Terraform stack (ADR-0047) | TBC | Platform (Neo Jia Wen) | `infrastructure/terraform/stacks/business-dashboard/**`, the CRM stack's `dashboard_inputs` output, `docs/runbooks/deploy-aws.md` | `task/infrastructure/business-dashboard-stack` | The CRM stack, applied first | Written; validated and plan-tested with mocked providers. Applied to AWS, where dashboard-service runs (confirmed 2026-10-20) | 2026-10-20 |
| BD-T12 | UI | TBC | Frontend | `frontend/src/pages`, `frontend/src/api/dashboard.*` | `task/frontend/real-data` | The mockup pages now read dashboard-service; panels it does not serve are hidden (open item 25) | Implemented; frontend tests, lint and build pass; not yet run against a deployed dashboard-service | 2026-10-16 |
| BD-T13 | Longer upstream budgets: 10 s per call and 15 s overall, from 5 s and 8 s (ADR-0033, amended 2026-10-20) | Neo Jia Wen | — | `services/dashboard-service/src/main/resources/application.yml`, README, ADR-0033 | `task/business-dashboard/longer-upstream-budgets` | Must stay under API Gateway's 29 s integration timeout | Implemented; dashboard-service tests pass; not yet checked in AWS | 2026-10-20 |
| BD-T14 | Daily trend charts evenly spaced in time: the services send only days with any rows, so the UI fills every day of the window with zero | Neo Jia Wen | — | `frontend/src/components/dashboard/figures.js` and its test, `pages/{Admin,Agent}BusinessDashboard.jsx` | `task/frontend/list-paging` | — | Implemented; frontend tests, lint and build pass | 2026-10-24 |

Merge path: `task/business-dashboard/<subtask>` → `feat/business-dashboard` → `dev`.

## 19. Skills

`SKILLS.md` does not exist on `dev`; none are approved for this feature.

## 20. Open questions

| # | Question | Owner | Blocks |
|---|---|---|---|
| Q1 | Who is the dashboard's primary developer? | Team | Task confirmation |
| Q2 | Does Joseph agree to ADR-0039 and to the endpoints on Feature 2 and 3 code? | Joseph Yau | Merging BD-T06 to BD-T08 |
| Q3 | Should ingestion freshness come back, with a last-successful-poll record in the receiver? | Joseph Yau | The freshness tile |
| Q4 | Is the stale-unverified threshold of 7 days right? | Team | — |
| Q5 | Is the per-agent table acceptable by default, given it ranks named staff? | Team | — |
