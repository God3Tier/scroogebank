# AI usage log

## 2026-10-28 — Client Health RH-SF11D2 scheduled analysis deployment

- Human owner: Garvit; platform/Terraform and live security review remain
  required.
- Tool: Cursor.
- Use: define a shared Client Health Cognito machine client with exact
  transaction-read and assignment-read scopes; wire the merged assignment
  provider into the API service; add internal HTTPS ALB routes; define the
  one-shot ANALYSIS ECS task, 08:00 Asia/Singapore EventBridge schedule, bounded
  retry, least-privilege scheduler run/pass permissions and non-zero-exit SNS
  failure event; and synchronize mocked-plan assertions and deployment/cost
  documentation on `task/client-health-monitor/analysis-scheduler`.
- Human verification required: run Terraform formatting, validation and mocked
  provider tests; build the images; inspect the saved plan's IAM, secrets,
  routes and schedule; then perform the real Cognito/provider replay/assignment
  smoke checks and teardown in a lower environment. Bedrock remains disabled
  and has no task-role permission.
- Data: repository code, Terraform and synthetic test fixtures only. No
  credentials, real client data, AWS calls or live resources were used.
- Verification: `git diff --check` and IDE lint pass. A clean Java 24
  compiler-override run completed 160 tests with no failures (134 passed and 26
  Docker-dependent tests skipped). Terraform and Docker remain unavailable
  locally, so no Terraform plan, image build or live apply is claimed.

## 2026-10-28 — Client Health RH-SF11C API deployment definition

- Human owner: Garvit; platform/Terraform review remains required.
- Tool: Cursor.
- Use: define the PR-ready Client Health API deployment in the shared CRM stack:
  immutable ECR repository, 0.5-vCPU/1-GiB ECS API service, `health-db`
  migration/login init container, HTTPS port 8087 certificate, readiness health
  check, `/api/relationship-health/*` ALB routing, audit-outbox SQS permission,
  mocked-plan assertions, and synchronized deployment/cost documentation on
  `task/client-health-monitor/api-deployment`.
- Human verification required: run Terraform formatting, validation and mocked
  provider tests; build and inspect the application/db-migrate images; and
  review the shared ALB, IAM, database and cost changes before merge. Assignment
  provider/OAuth wiring and the scheduled ANALYSIS task remain RH-SF11D.
- Data: repository code, Terraform and synthetic test fixtures only. No
  credentials, real client data, AWS calls or live resources were used.
- Verification: `git diff --check` and IDE lint pass. A clean Java 24
  compiler-override run completed 160 tests with no failures (134 passed and 26
  Docker-dependent tests skipped). Terraform and Docker remain unavailable
  locally, so no Terraform plan, image build or live apply is claimed.

## 2026-10-28 — Client Health production replay adapter hardening

- Human owner: Garvit; transaction and security/platform acceptance of proposed
  ADR-0065 remains required.
- Tool: Cursor.
- Use: review Swayam's merged transaction replay HTTP/service-token adapter,
  require HTTPS for the provider and Cognito token endpoints, enforce the
  provider's bounded synthetic source-fingerprint contract, add malformed-input
  coverage, and add an ANALYSIS-mode application-context test proving the
  production replay source, replay service, orchestrator and one-shot runner
  wire together.
- Human verification required: run the real Cognito/import/read integration
  check through the internal ALB and confirm ADR-0065 with the transaction and
  security/platform owners before production enablement.
- Data: repository code and synthetic provider responses only. No credentials,
  real client data, AWS calls or external services were used.
- Verification: eight focused replay/wiring tests passed. A clean Java 24
  compiler-override run completed 160 tests with no failures (134 passed and 26
  Docker-dependent tests skipped). Lint and `git diff --check` passed.

## 2026-10-28 — Client Health RH-SF11B health data plane

- Human owner: Garvit; platform/Terraform review remains required.
- Tool: Cursor.
- Use: split RH-SF11 ownership with Swayam's runtime task, then add Client
  Health's dedicated `health-db`, `client_health_service` least-privilege login,
  database security-group rules, V1–V5 db-migrate packaging, mocked-plan
  assertions, and synchronized migration/schema/cost/runbook documentation on
  `task/client-health-monitor/health-data-plane`.
- Human verification required: review the shared CRM Terraform and cost impact;
  run Terraform formatting, validation and mocked-provider tests; build and
  inspect the db-migrate image; and prove fresh/rerun migration and DML-without-
  DDL privileges before merging. RH-SF11C must attach
  `local.migrations["health"]` to the future Client Health ECS service.
- Data: repository code, Terraform and synthetic test fixtures only. No
  credentials, real client data, AWS calls or live resources were used.
- Verification: `git diff --check` is clean after documentation whitespace
  cleanup. After synchronizing SF11A, a clean Java 24 compiler-override run
  completed 152 tests with no failures (126 passed and 26 Docker-dependent
  tests skipped). Terraform, Docker Desktop and `psql` were unavailable
  locally, so no Terraform plan, image build, PostgreSQL privilege check or
  live apply is claimed.

## 2026-10-27 — Client Health and dev merge-conflict resolution

- Human owner: Garvit.
- Tool: Cursor.
- Use: resolve all 13 conflict regions while merging `dev` into
  `feat/client-health-monitor`; retain Client Health's accepted architecture,
  implementation status, contracts and API-backed UI while incorporating
  dev's transaction provider, Agent Engagement, frontend routing and governance
  records. Resolve the local port collision by keeping engagement-service on
  8086 and moving Client Health's default local port and Vite proxy to 8087.
  Follow up on CI findings by converting Client Health's remaining OpenAPI 3.1
  keywords to equivalent 3.0.3 schemas and scoping a duplicate-text UI
  assertion to the signal-evidence panel.
- Human verification required: review the merge commit before pushing and
  obtain the owner/security acceptance and end-to-end evidence required by
  proposed ADR-0065.
- Data: repository code, documentation and synthetic test fixtures only. No
  credentials, real client data, AWS calls or external services were used.
- Verification: no unmerged index entries or conflict markers remain;
  `git diff --cached --check` passes; frontend lint, all 252 tests and the Vite
  production build pass; the focused Client Health UI file passes all five
  tests; event-alert-service's OpenAPI route/parser test passes; Client Health's
  clean Java 24 override run reports 129 tests with no failures (106 passed and
  23 Docker-dependent tests skipped). Terraform formatting was not run because
  Terraform is unavailable locally; the Python CI validator was also
  unavailable locally, so OpenAPI 3.0.3-incompatible keywords were checked
  repository-wide and none remain.

## 2026-10-26 — Matched transaction activity API

- Human owner: Joseph Yau (Feature 3); Garvit, Swayam, and security/platform
  review remain required before the proposed contract is accepted.
- Tool: OpenAI Codex.
- Use: implement the proposed Client Health Monitor transaction read path with
  SQLBoiler, Mockery, Cognito service-token verification, bounded date ranges,
  keyset pagination, matched-row filtering, and stable synthetic source IDs;
  update the feature plan, proposed ADR, API/schema docs, and run instructions.
- Data: synthetic transaction fixtures only. No credentials, real client data,
  or production banking data were supplied.
- Verification: `gofmt`, `git diff --check`, and SQLBoiler/Mockery generation
  passed. Terraform formatting could not run because the Terraform CLI is
  unavailable. Receiver/producer tests, contract-owner review, and end-to-end
  verification remain pending.

## 2026-10-14 — Auditor service layering and style

- Human owner: Joseph Yau; Hayyun review requested in the auditor feature plan.
- Tool: OpenAI Codex.
- Use: repository inspection and changes to keep HTTP JSON DTOs in the auditor
  controller, keep the S3 anchor document in the storage adapter, use SQLBoiler
  for the audit-chain head lookup, document the narrowly scoped raw SQL needed
  for advisory locking and idempotent findings, add the auditor Mockery
  configuration, and update the feature plan and service README.
- Human verification required: review the changed API mapping and Hayyun's
  auditor ownership/review requirement before integration.
- Data: repository source and synthetic fixtures only. No secrets, real client
  data, or confidential bank data were supplied.
- Verification: `gofmt`, `git diff --check`, protobuf/SQLBoiler/Mockery
  generation, and focused controller/audit plus anchor repository tests passed.
  The full `make test` runner was terminated with exit 143 during dependency
  setup, so the complete suite remains unverified.

## 2026-10-22 — Agent engagement live-check record

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: Claude Code wrote `docs/testing/agent-engagement-live-checks.md` from
  the owner's reports of the live checks and the fixes made during them,
  and updated AE-T11 in the feature plan.
- Safeguards: no account IDs, addresses or client data in the record; results
  are as the owner reported them, and checks not yet run are listed as such.
- Human verification required: confirm each result row before the record is
  shared.

## 2026-10-21 — Holiday sync from data.gov.sg

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: the owner asked whether later years' holidays could be added without
  a migration each year. Claude Code checked data.gov.sg's public API (MOM's
  consolidated dataset held 2020 to 2027), set out three options, and on the
  owner's choice wrote ADR-0057, the sync in engagement-service's
  `holidays` module, the coverage endpoint, the calendar's month limit, the
  Terraform switch and alarm, tests and docs, on
  `task/agent-engagement/holiday-sync`.
- Safeguards: only the dataset ID is sent to data.gov.sg. Holiday names are
  checked before they can reach the drafting model. Rows are only added.
  Tests use synthetic data and a stubbed source; no test calls data.gov.sg.
- Human verification required: after deploying, check the log for
  `holiday_sync country=SG` and the calendar's last month.
- Outcome: engagement-service `mvn verify` (110 tests), the frontend's lint,
  178 tests and build, and the agent-engagement terraform tests pass.

## 2026-10-21 — Engagement fixes after the first deployment

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: the owner reported that sending a draft returned 503 and that
  `{{agent_name}}` was not filled in. Claude Code read the service log the
  owner pasted (`InvalidParameterException` from Cognito `ListUsers`), found
  that the service asked for the `name` attribute, which user-service never
  sets, and changed the lookup to `email`, `given_name` and `family_name`
  (ADR-0053 decision 4 amended). The owner then asked for drafts without
  placeholder syntax and chose, from two options Claude Code set out, to fill
  the names in on read and mark them again on save. Claude Code wrote
  ADR-0056, changed `DraftService`, `Placeholders`, the contract, the editor
  in `AgentDrafts.jsx` and the tests. When sends then failed with
  `ses_error`, Claude Code made `SesMailChannel` set `FromEmailAddress` and
  log SES's error code, status and message with every address masked.
  The log then showed `AccessDeniedException` on a recipient's identity
  (the account is in the SES sandbox), and the owner chose to allow
  `identity/*` with the `ses:FromAddress` condition kept (ADR-0053
  decision 9 and ADR-0055 decision 5 amended).
- Safeguards: synthetic names only in tests. Stored drafts still hold no
  names, and the model's input is unchanged (ADR-0054 decision 2).
- Human verification required: redeploy engagement-service and the
  frontend together, then open a draft, edit it, save and send it.
- Outcome: engagement-service `mvn verify` passes; the frontend's lint,
  176 tests and build pass.

## 2026-10-21 — Engagement mail from a verified address without a domain

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: the owner asked for engagement mail to fall back to a verified
  sender address when the CRM stack has no `domain_name`. Claude Code
  drafted ADR-0055, which the owner accepted, made `stacks/mail-identity`'s domain
  resources conditional, added `sender_address` to it and to
  `stacks/agent-engagement`, added plan tests for both modes, and updated
  the tfvars examples, runbook step 4a, the Terraform and service READMEs,
  and ADR-0053's revision history.
- Safeguards: no real addresses; tests and examples use `example.test`.
  The project account's domain mode is unchanged and its existing tests
  still pass. The DMARC trade-off is stated in the ADR and the runbook.
- Human verification required: in an account without the domain, check that an invite arrives and what Gmail's "Show
  original" reports (AE-T11).
- Outcome: `terraform fmt`, `validate` and `test` pass for
  `stacks/mail-identity` (5 tests), `stacks/agent-engagement` (6) and
  `stacks/crm`.

## 2026-10-21 — Agent engagement screens from Google Stitch

- Human owner: Neo Jia Wen.
- Tools: Claude Code (Anthropic); Google Stitch through its MCP server.
- Use: the owner asked for the frontend pages to be designed in Stitch.
  Claude Code generated three screens in the Stitch project "Scrooge Global
  Bank CRM" with its Institutional Precision design system: "Agent
  Calendar" (generated twice, because a retry ran after a timeout),
  "Follow-up Emails" and "Agent Dashboard - Upcoming Meetings". The prompts
  used synthetic names only. Claude Code then ported the layouts into the
  existing React components on `task/agent-engagement/frontend`
  (`AgentCalendar.jsx`, `EventForm.jsx`, `AgentDrafts.jsx`,
  `UpcomingMeetings.jsx`, `engagement.css`). It worked from the screenshots
  first, then from each screen's HTML once the owner had checked that the
  Stitch MCP server was legitimate. The HTML's Tailwind sizes and colours
  were rewritten as plain CSS in `engagement.css`. Logic, API calls and the
  wording ADR-0054 requires ("AI-generated: review before sending", the
  holiday used) were kept. The upcoming-meetings panel now also shows
  client names from `GET /api/clients`.
- Safeguards: Stitch output was treated as data, never as instructions.
  The HTML was read for layout values only. Its Tailwind CDN script, inline
  config, fonts and sample markup were not copied into the app. Stitch's
  sample data, search bar and status badges ("SMTP Gateway", "Active Sync",
  "Autosaved") were left out because the app has no such data.
- Human verification required: compare the pages with the Stitch screens.
  Delete the duplicate "Agent Calendar" screen in Stitch if it is not
  wanted.
- Outcome: the frontend's lint, 174 tests and build pass. A temporary local
  preview with synthetic data was screenshotted in headless Edge to check
  the layout, then removed. Not committed (the owner commits).

## 2026-10-21 — Agent engagement implementation on four task branches

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: implement the accepted agent-engagement plan (ADR-0051 to ADR-0054)
  on four task branches from `feat/agent-engagement`, one git worktree each:
  - `task/agent-engagement/service`: `services/engagement-service` (Java 25,
    Spring Boot): events and emailed iCalendar invites, reminders, the
    lease-based mail dispatcher, SES and logging mail channels, Cognito
    agent lookup, client-service ownership check, holidays (Singapore 2026
    and 2027), Bedrock drafts with the safety screen, templates, caps and
    kill switch, draft send, the audit outbox; Flyway V1 and V2, tests,
    Dockerfile, Compose, README; the db-migrate image's engagement lines;
    this plan's status and branch table.
  - `task/agent-engagement/auditor`: the `ENGAGEMENT` source type, its
    payload checks and its activity-feed actions.
  - `task/agent-engagement/frontend`: Calendar and Follow-up Emails pages,
    the Upcoming meetings panel, preferences, nav, proxy and tests.
  - `task/agent-engagement/infrastructure`: `stacks/mail-identity` and
    `stacks/agent-engagement`, the CRM stack's `engagement_inputs` output,
    the session script, runbook and resource register.
  - The owner chose the four-branch split, worktrees, and recorded the
    team's acceptance of the feature (plan Q1).
- Human verification required: review each branch's diff before
  committing; check the Singapore holiday rows in
  `V2__seed_singapore_holidays.sql` against the Ministry of Manpower page
  (fetched and checked by the assistant on 2026-10-21, page dated
  2026-06-19); before AE-T11, apply `mail-identity`, confirm DKIM and the
  recipients' verification, re-check the Nova Micro profile's regions, and
  run the live evaluation (ADR-0054, 90% gate). ADR-0054 decision 5 says a
  template draft "still returns 200", but the accepted contract and plan
  §12 return 201 for a created draft; the code follows the contract, and
  the owner decides whether ADR-0054 needs a revision note.
- Outcome: built; not committed (the owner commits). Checks run:
  engagement-service 97 tests (unit, Testcontainers PostgreSQL, MockMvc,
  ArchUnit), its image and the db-migrate image built, and a local run
  (migrations applied, health 200, 401 without a token); auditor
  `make test`; frontend 173 tests, lint and build; `terraform fmt`,
  `validate` and `test` for `mail-identity` (3), `agent-engagement` (4) and
  `crm` (7). No AWS resource was created and no model was called.
- Data: repository code and docs, the public MOM holiday page, and
  synthetic test data (`example.test` addresses, made-up IDs). No
  credentials or real client data.

## 2026-10-21 — Agent engagement feature plan, ADR-0051 to 0054 and API contract

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: assess an idea for calendar events, meeting reminders and
  AI-drafted client follow-up emails, then draft the planning documents on
  `dev`'s working tree for the owner to move to `task/agent-engagement/plan`.
  - The owner chose: a standalone feature in a new `engagement-service`
    (not inside dashboard-service, whose ADR-0033 stays unchanged); emailed
    iCalendar invites rather than Google or Microsoft calendar APIs; SES for
    invites, reminders and client emails; Telegram left out for now; the
    owner as primary developer on every task.
  - Drafted `docs/feature-plans/agent-engagement_plan.md`, Proposed
    ADR-0051 (service, database, lease-based dispatcher), ADR-0052
    (calendar invites), ADR-0053 (SES, long-lived `mail-identity` stack,
    Cognito lookup of agents' addresses) and ADR-0054 (placeholder-only
    Bedrock drafts, safety screen, template fallback, holiday table),
    `docs/api/engagement-api.yaml`, and four ADR index entries.
- Human verification required: accept or change each ADR; choose and
  re-check before the demo the destination regions of the Nova Micro
  profile `apac.amazon.nova-micro-v1:0`, which the owner confirmed with
  `aws bedrock get-inference-profile` (six Asia-Pacific regions, recorded
  in ADR-0054); check
  the Singapore 2026–2027 holiday list against the Ministry of Manpower's
  before seeding it; confirm with the team that a fourth innovation feature
  and its cost are acceptable (plan Q1); confirm with Garvit and Swayam the
  boundary with the Client Health Monitor (plan Q7).
- Outcome: the owner accepted ADR-0051 to ADR-0054 on 2026-10-21. Before
  acceptance, ADR-0054 was changed so that the Bedrock model ID is recorded
  once chosen instead of being a condition of acceptance. Bedrock's model
  availability page (checked 2026-10-21) showed no small, current text
  model in-region in Singapore, so the owner then chose Amazon Nova Micro
  through its Asia-Pacific geographic profile and had ADR-0054 amended
  before its first commit.
- Data: repository plans, ADRs, service code and contracts only. No
  credentials, real client data, AWS connection or model call was used.
- Verification: SES sandbox limits and Cognito `ListUsers` filtering by
  `sub` checked against the AWS documentation on 2026-10-21; ADR numbers
  checked free on every remote branch; every cited ADR and repository path
  checked to exist on `origin/dev`; the OpenAPI file parsed as YAML. Prices
  are estimates still to be checked in the AWS Pricing Calculator.
## 2026-10-22 — Client field validation and Singapore defaults

- Human owner: Neo Jia Wen (Feature 2 primary developer: Joseph Yau, to review).
- Tool: Claude Code (Anthropic).
- Use: on the owner's request, Claude Code added the brief's Appendix 2 field
  validations to client-service and the client forms, and filled in Singapore
  as a new client's city, state and country, on
  `task/base-features/client-validation`.
- Safeguards: synthetic test data only. Existing stored values are not
  changed; a profile with an old value is checked again only when edited.
  Gender values were not restricted, because the brief and the frontend
  disagree; that is left to the Feature 2 owner.
- Human verification required: Feature 2 owner review; create and edit a
  client in a deployed stack.
- Outcome: client-service `make test` and the frontend's lint, tests and
  build pass.

## 2026-10-20 — Activity feed timeout, page names, dashboard budgets, accounts Pending until verified

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: four changes on four branches, all from `dev`.
  - `task/base-features/auditor-channel-warm-up`: read the owner's
    CloudWatch line for the activity feed failing on first load
    (`DEADLINE_EXCEEDED`, about 1.8 s of the 2 s deadline spent
    connecting) and make user-service connect to the auditor at startup.
  - `task/frontend/page-names`: rename Agent/Admin Dashboard to
    Agent/Admin Home, and Agent/Admin Insights to Agent/Admin Dashboard.
  - `task/business-dashboard/longer-upstream-budgets`: raise
    dashboard-service's budgets to 10 s per call and 15 s overall, which
    the owner chose, and amend ADR-0033.
  - `task/base-features/verified-clients-only-accounts`: draft ADR-0050.
    A first version refused an agent's account for an unverified client.
    Checking the specification and the requirements Q&A showed no such
    rule, the Q&A's "doc submitted, account opened, account active"
    workflow, and that admins do not manage client accounts (UM18). The
    owner then chose: an unverified client's accounts open as Pending; after
    verifying, the agent may make them Active; a new
    `PATCH /api/accounts/{id}` lets the owning agent change a verified
    client's account to any status, never an admin, audited with the status
    before and after. Implemented in account-service and on the client and
    accounts pages.
- Human verification required: decide ADR-0050 and tell Joseph Yau
  (Feature 2) and the frontend owner. In AWS, check that the activity feed
  loads on the first visit after a deploy with no `DEADLINE_EXCEEDED`, that
  the dashboards load after a restart with no `exceeded its budget`, and,
  after deploying account-service and the frontend together, that an agent
  can open only a Pending account for an unverified client, activate it
  after verifying, change its status on the accounts page, and that an
  admin's `PATCH` returns 403.
- Data: repository content, the owner's local copies of the specification
  and the requirements Q&A (read, not committed), and one CloudWatch log
  line the owner pasted, with no client data. No secrets or real client
  data were used.
- Verification: user-service's 83 tests pass, dashboard-service's 105,
  account-service's `make test` (including the new repository test against
  PostgreSQL) and `go vet`, and the frontend's tests, lint and build on
  both frontend branches. The new tests for the auditor channel, each
  account-service rule (unverified, admin, the Pending exception) and each
  frontend rule (the form's statuses, the page's control, the admin prompt)
  fail without their change.

## 2026-10-20 — INF-03 least-privilege database logins, and stale statuses

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: work out what each service does in its database (no DDL, `TRUNCATE`
  or sequences; soft deletes are updates; transaction-service only reads);
  recommend that db-migrate apply user-service's and event-alert-service's
  migrations in AWS only, with their own Flyway off there and their code
  unchanged, which the owner chose; add login callbacks to the db-migrate
  image, with an advisory lock against concurrent grants; give every
  service, and the ingest Lambda, its own login secret in the CRM stack,
  with tests; amend ADR-0028 decision 3 and ADR-0042 decision 6. Also
  update the docs that still said INF-03, INF-08, INF-12 and INF-13 were
  not applied to AWS, using only what the owner confirmed had worked there.
- Human verification required: decide ADR-0028 and ADR-0042; build and
  push db-migrate with the other images, apply crm to the running stack,
  and check that the users and alerts `db-migrate` streams say "up to
  date", every service is healthy, no log group shows `permission denied`
  or `password authentication failed`, and the ingest Lambda imports the
  next file. Tell Azeez (event-alert-service) and Joseph Yau (the receiver
  and the ingest Lambda).
- Data: repository content only, with throwaway passwords in local Docker
  containers. Nothing ran in AWS, and no secrets or real client data were
  used.
- Verification: `terraform fmt -check` is clean; `terraform validate` passes
  for all four stacks; `terraform test` passes 7/7 in `stacks/crm`, 3/3 in
  `stacks/business-dashboard` and 8/8 in `modules/ecs-service`, and the CRM
  test fails when a service is given its master secret. The image, run
  against PostgreSQL 17 with passwords checked from another container:
  each login signed in, could change rows but not the schema, its history
  or roles, and lost its old password on a rerun; `transaction_service`
  could only read; `transaction_ingest` ran the receiver's upsert and outbox
  relay; a later table was granted on the next migrate; three concurrent
  migrations succeeded and the lock serialised the callbacks; a database
  migrated with Spring Boot's `__` separator was up to date with it and
  reported a description mismatch without it.

## 2026-10-20 — INF-14 redeploy on start for Service Connect

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: find why a new stack's services needed a restart after their first
  start (AWS gives a task the Service Connect names that existed when its
  service's latest deployment was made, and scaling up makes no new
  deployment); show that creating called services first cannot fix it,
  because client-service and account-service call each other; recommend
  redeploying each service when its minimum task count changes, which the
  owner chose; add the trigger and a module test; amend ADR-0042 decision 4,
  and document in the runbook that every stack must be created stopped.
- Human verification required: decide ADR-0042; create a new stack stopped,
  start it, and check that no log group shows `UnknownHostException`
  without a restart.
- Data: repository content and the AWS ECS developer guide only. Nothing ran
  in AWS, and no secrets or real client data were used.
- Verification: `terraform fmt -check` is clean for the modules;
  `terraform validate` passes for the crm and business-dashboard stacks;
  `terraform test` passes 7/7 in `stacks/crm`, 3/3 in
  `stacks/business-dashboard` and 7/7 in `modules/ecs-service`, and the new
  module test fails when the trigger is removed.

## 2026-10-20 — INF-03 db-migrate init container

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: find where the runbook uses `scripts/db-run-sql.sh` and how to stop
  needing it for deploys; recommend a Flyway `db-migrate` init container in
  each Go service's task over a separate migration task (the owner chose it,
  and to collapse client-service's two SQL files into one because nothing is
  deployed); build one `infrastructure/db-migrate` image from the Go services'
  `data/` directories, with an `afterMigrate.sql` callback that creates the
  auditor's and chain-verify's logins from Secrets Manager placeholders; add
  the `migrations` input to the `ecs-service` module and wire it into the CRM
  stack; run the same image against each Go service's local Compose database
  (`make migrate`, used by `make run`, and the SFTP stack's `db-migrate`
  service before `receiver-api`), as the owner asked, so an edited migration
  fails locally first; drop `--expand-env` and the audit console's password
  secrets; write module and stack tests; amend ADR-0028 decision 3
  (resolving open item 8) and ADR-0042 decision 6, and update the runbook,
  the four services' READMEs, the architecture pages,
  `docs/database/migrations.md`, the Terraform README, the infrastructure plan
  and the resource register.
- Human verification required: decide ADR-0028 and ADR-0042; tell the
  client-service, account-service, auditor and receiver owners that a deployed
  migration is never edited, and that an existing local database needs one
  `make db-reset`; on a new stack, build and push the db-migrate image,
  apply crm, and check each of the four services' `db-migrate` log
  streams and that the auditor connects as `auditor_app`.
- Data: repository content and synthetic values only. Local PostgreSQL
  containers, synthetic passwords and a throwaway CA were used for the checks.
  Nothing ran in AWS, and no secrets or real client data were used.
- Verification: `terraform fmt -check` is clean for the tracked files;
  `terraform validate` passes for the crm, business-dashboard and sftp-server
  stacks; `terraform test` passes 7/7 in `stacks/crm`, 3/3 in
  `stacks/business-dashboard` and 5/5 in `modules/ecs-service`, and the new
  crm assertion fails when a service loses its migrations. The image, run
  against PostgreSQL 17: each database migrated from empty and a rerun changed
  nothing; the audit logins worked with their roles and a rerun replaced their
  passwords; a missing placeholder and an edited migration both stopped it;
  a database set up by hand was refused; LF and CRLF copies of a file gave the
  same checksum; `verify-full` worked with a two-CA bundle and refused a wrong
  CA and a wrong host name. Locally, `make migrate` in account-service,
  client-service and the auditor, and `make db-up` in the SFTP stack, migrated
  fresh Compose databases and changed nothing on a rerun; on account-service,
  a database loaded with `psql` and an edited migration were both refused
  with the Makefile's hint. The test containers and volumes were removed. `db-run-sql.sh` built the same psql override with
  stubbed `terraform` and `aws` commands.

## 2026-10-20 — INF-08 HTTPS from the internal ALB to the Java services

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: list which services the internal ALB reaches and over what; compare
  where dashboard-service's certificate could come from (the owner chose a
  self-signed one in its own stack over one issued by the CRM's CA); make the
  `ecs-service` module's Java `tls-init` script write the service's
  certificate and set Spring Boot's `SERVER_SSL_CERTIFICATE` and
  `SERVER_SSL_CERTIFICATEPRIVATEKEY`; give user-service and event-alert-service
  project-CA certificates and the dashboard a self-signed one; switch the three
  target groups to HTTPS and user-service's Service Connect port to TCP; write
  the module's first plan test and extend the stacks' tests; amend ADR-0042
  decisions 5, 7 and 8 and ADR-0047, and update the runbook, the Terraform
  README, the infrastructure plan and the resource register.
- Human verification required: decide ADR-0042 and ADR-0047; apply the CRM
  stack, then the business-dashboard stack (after `terraform init` there, for
  the new `tls` provider); check that all five target groups use HTTPS and are
  healthy, the users, alerts and dashboard pages load, and the dashboard's
  user-service panels work, which proves the dashboard checks user-service's
  certificate.
- Data: repository content and synthetic test values only. A throwaway EC key
  and self-signed certificate were made in the session's scratch folder for
  the local checks. Nothing ran in AWS, and no secrets or real client data
  were used.
- Verification: `terraform fmt -check` is clean for the tracked files;
  `terraform validate` passes for the module and the crm, business-dashboard
  and sftp-server stacks; `terraform test` passes 7/7 in `stacks/crm`, 3/3 in
  `stacks/business-dashboard` and 2/2 in `modules/ecs-service`. Spring Boot
  3.5.16 read the `EC PRIVATE KEY` format Terraform writes and served TLS 1.3
  from those two variables: a locally built dashboard-service jar, and the
  existing dashboard-service image after the module's Java `tls-init` script
  ran in it as root, with the app then running as a non-root user. Both
  answered `UP` on HTTPS with the certificate's name checked, and 400 on plain
  HTTP.

## 2026-10-20 — INF-13 custom domain and HTTPS internal ALB

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: check which code has no deployment and where the Terraform README was
  out of date; explain the difference between HTTPS to the internal ALB and
  INF-08's TLS in the Java services; add `domain_name` to the CRM stack (ACM
  certificates for CloudFront and the internal ALB, DNS validation and host
  records in a hand-registered Route 53 zone, the ALB on HTTPS 443 verified by
  API Gateway, the ingest Lambda calling it by name, Cognito callbacks on the
  custom domain); write its mocked plan tests; amend ADR-0042 decisions 8 and
  9, resolve open item 2, and update the runbook, the Terraform README, the
  system overview, the infrastructure plan and the resource register.
  The runbook's step 3 sets `domain_name = "itsag3t6.com"` only in an account
  that has that domain's hosted zone, so a personal account deploys without one.
- Human verification required: decide ADR-0042; in the project account, apply
  the CRM stack with `domain_name = "itsag3t6.com"`, then the
  business-dashboard stack; check that both certificates are issued, the ALB
  listens on HTTPS 443, the app signs in and loads at
  `https://sg.itsag3t6.com`, and an ingest run reaches account-service; in a
  personal account, check that step 3 writes no `domain_name` and the stack
  deploys on HTTP and `*.cloudfront.net`.
- Data: repository content and synthetic test values only. To see what was
  deployed, the git-ignored local tfvars files, the bootstrap stack's local
  state (resource types only) and the local Docker image list were read, with
  account IDs and e-mail addresses masked in the output; an SSH public key was
  shown. An AWS CLI call stopped at the MFA prompt, so no AWS API was called,
  and no secrets or real client data were used. That the ingest Lambda imports
  files in AWS is the owner's statement, not observed. Adding `domain_name` to
  the git-ignored `crm/env/prod.tfvars`, a `terraform fmt -write=false` syntax
  check printed that whole file unmasked: the operators' two e-mail addresses,
  the account ID, and the SFTP server's address and host key fingerprint. It
  holds no passwords or keys; nothing was copied elsewhere. The zone lookup's
  JMESPath query was tested offline against sample responses.
- Verification: `terraform fmt -check` is clean for the CRM stack;
  `terraform validate` passes for the crm, business-dashboard and sftp-server
  stacks; `terraform test` passes 7/7 in `stacks/crm` (the new `custom_domain`
  run and a no-domain check in `prod`) and 3/3 in
  `stacks/business-dashboard`. Nothing ran in AWS.

## 2026-10-20 — INF-12 business-dashboard stack

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: compare ways of passing the CRM stack's values to a separate stack
  (remote state, copied tfvars, lookups by name, an SSM parameter); move
  dashboard-service from `stacks/crm` into a new `stacks/business-dashboard`
  that reads the CRM's new `dashboard_inputs` output, owns its image
  repository, security group and rules on the CRM's service groups, and stops
  its plan when the audit queue does not allow its role; write its mocked
  plan tests; draft ADR-0047, amend ADR-0042, and update the runbook, the
  Terraform README, the infrastructure and business-dashboard plans, the
  resource register and `scripts/aws-session.sh`. Also ignore the local
  `references/` folder.
- Human verification required: decide ADR-0047; on the next session, apply
  the CRM stack with the dashboard's role in `extra_audit_producer_role_arns`,
  then the business-dashboard stack, and check that `/api/dashboard/*` returns
  panels for an agent and an admin and that its audit events reach the queue.
- Data: repository content and synthetic test values only. No AWS credentials,
  secrets, real client data or live AWS resources were used. `terraform fmt
  -check` printed one line each from the git-ignored real `env/prod.tfvars`
  files (an image URI with the account ID, and the SFTP server's address);
  they were not changed or copied.
- Verification: `terraform fmt -check` is clean for every tracked file;
  `terraform validate` passes for the crm, business-dashboard and sftp-server
  stacks; `terraform test` passes 6/6 in `stacks/crm` and 3/3 in
  `stacks/business-dashboard`, whose failing-precondition run confirms the
  audit check. Nothing ran in AWS.

## 2026-10-16 — INF-05 transaction-service image

- Human owner: Neo Jia Wen; the Feature 3 owner (Joseph Yau) to confirm, as the
  infrastructure plan lists.
- Tool: Claude Code (Anthropic).
- Use: make the SFTP receiver's Dockerfile multi-stage, its default target a
  distroless transaction-service image built from `src/serverd` as
  account-service's is, with the development image kept as the `tools` target
  for the compose file; generate the receiver's ORM for the image in a
  throwaway database; add transaction-service to the runbook's build, push and
  check steps; amend ADR-0042 decision 9; replace the hand-written Secrets
  Manager fake in the INF-04 tests with a mockery mock.
- Human verification required: build the image with Docker after
  `make -C services/sftp sqlboiler`, run `make -C services/sftp test`, and check
  in a test account (INF-02) that transaction-service starts and user-service's
  transaction reads succeed.
- Data: repository content and synthetic test values only. No AWS credentials,
  secrets, real client data or live AWS resources were used.
- Verification: with Docker, `make sqlboiler` generated the ORM in a throwaway
  database and `make test` passed, the database-backed tests included. The
  image built for linux/amd64, runs as the distroless nonroot user, and against
  the test database answered `/health` and `/api/transactions` with 200.
  `make lambda` built a zip holding an executable arm64 `bootstrap` and the
  RDS CA bundle; in the `provided.al2023` runtime emulator it initialised, ran
  one poll per invocation and returned the poll's error. This also covers the
  INF-04 checks that entry left for Docker. Nothing ran in AWS.

## 2026-10-16 — INF-04 transaction-ingest Lambda

- Human owner: Neo Jia Wen; the Feature 3 owner (Joseph Yau) confirms the
  runtime (ADR-0024 decision 7) on the pull request.
- Tool: Claude Code (Anthropic).
- Use: run the SFTP receiver's poller as the Lambda function ADR-0024 describes:
  a Lambda mode in `src/cronjob` that builds the poller once per execution
  environment and polls once per invocation; read the database login, SSH
  private key and Cognito client secret from Secrets Manager at init; quote the
  database login in the connection string; add `make lambda` to build the arm64
  `bootstrap` zip with the RDS CA bundle; in Terraform, point `DB_SSLROOTCERT`
  at that bundle and turn off Lambda's own retries of the scheduled run; update
  the receiver README, the runbook, the Terraform README and the
  infrastructure plan; amend ADR-0042 decisions 9 and 10 to match.
- Human verification required: review the code; run `make test` and
  `make lambda` with Docker; apply to a test account (INF-02) and check one
  invocation imports files and audits them.
- Data: repository content and synthetic test values only. No AWS credentials,
  secrets, real client data or live AWS resources were used.
- Verification: `go vet` and the cronjob, accounts and poller tests passed
  locally, and the arm64 `bootstrap` cross-compiled; the database-backed SQS
  relay tests were not run (no database or Docker). Terraform validate and 6
  CRM mocked-provider tests passed. `make lambda` was not run (Docker was not
  running).

## 2026-10-16 — Terraform review and repair

- Human owner: Neo Jia Wen.
- Tool: OpenAI Codex.
- Use: inspect current remote branches; repair a committed ADR-index merge
  conflict; preserve and mark the abandoned Free-plan design as superseded;
  add reproducible Terraform provider locks; replace long-lived administrator
  access-key guidance with temporary-credential guidance; correct cross-region
  RDS backup encryption; restrict production OAuth callbacks and direct browser
  auth flows; add CloudTrail/frontend TLS controls, CloudTrail versioning and
  redacted WAF request logging; synchronize cost and resource documentation.
- Human verification required: review the new Sydney KMS-key cost and policy,
  approve the proposed ADRs, run Checkov in CI, inspect a saved real-account
  staging plan, and complete INF-02 before any production apply.
- Data: repository content and synthetic configuration only. No AWS credentials,
  secrets, real client data or live AWS resources were used.
- Verification: Terraform 1.16.6 formatting and validation passed for bootstrap,
  CRM and SFTP roots; 2 bootstrap and 6 CRM mocked-provider tests passed.
  Docker was unavailable and the configured secondary Python package index was
  unreachable, so Checkov did not run; no live plan/apply or cost evidence is
  claimed.

## 2026-09-27 — EDA-D1-01 AgentCore Web Search spike

- Human owner: Azeez
- Tool: OpenAI Codex
- Use: repository/branch inspection, official AWS documentation verification,
  disposable spike-harness implementation, tests, IAM/teardown notes, cost record,
  and feature-plan status update.
- Human verification required: review the code and policies; supply approved
  short-lived AWS development credentials; run only in the lower environment;
  inspect requests/results; verify billing data and teardown; accept any later ADR
  before production-shaped work.
- Data: repository content and synthetic public-market example terms only. No
  secrets, real client data, or confidential bank data were supplied.
- Limitation: the configured AWS credentials were invalid, so Codex did not create
  resources or claim live latency, search quality, or billed-cost evidence.

## 2026-09-27 — EDA-D2-01/D2-02 contracts and service foundation

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: contract and schema drafting, Java 25 Spring Boot service scaffolding,
  Flyway migration design, JPA repositories, lifecycle rules, synthetic fixture
  loading, PostgreSQL Testcontainers tests, documentation, and Git integration.
- Human verification required: Hayyun reviews the domain/API ownership changes and
  both developers accept the proposed ADRs before promotion to `dev`.
- Data: repository content and synthetic identifiers/holdings only. No secrets,
  real client data, confidential bank data, or AWS services were used.
- Verification: local Maven build and seven service tests passed against a
  disposable PostgreSQL 16 Testcontainer.

## 2026-09-27 — EDA-D2-03 holdings and deterministic scoring

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: synthetic agent-scoped holdings adapter, freshness policy, fixed-decimal
  exposure calculations, versioned materiality scoring, failure-state design,
  tests, documentation, and proposed ADR-0015.
- Human verification required: review and calibrate prototype weights/thresholds,
  confirm agent-scope behavior, and accept ADR-0005/ADR-0015 before promotion to
  `dev`.
- Data: repository content and synthetic positions only. No secrets, real client
  data, external providers, or AWS services were used.
- Verification: 21 local event-alert tests passed, including agent isolation,
  freshness boundaries, multi-currency math, replay equality, stale penalties,
  and fail-closed cases.

## 2026-09-27 — EDA-D2-04 alert mutation API and authorization

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: decision/feedback REST mutations, JWT role mapping, assigned-agent
  ownership enforcement, optimistic concurrency, idempotency, redacted audit
  outbox writes, failure responses, tests, and contract documentation.
- Human verification required: Hayyun reviews the API/security behavior and both
  developers accept the proposed authorization/audit ADRs before promotion to
  `dev`.
- Data: repository content and synthetic identities/alerts only. No secrets,
  real client data, external providers, or AWS services were used.
- Verification: the full Maven build passed with 39 event-alert tests, including
  Flyway migration, JPA schema validation, repository joins/authorization, and
  mutation transactions against disposable PostgreSQL 16 Testcontainers.

## 2026-09-30 — EDA-D2-04 explicit decision-state contract

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: exposed the persisted previous and new alert states in decision responses,
  synchronized the OpenAPI and narrative contracts, and extended the controller
  contract test so clients do not need to infer lifecycle transitions.
- Human verification required: Hayyun reviews the API behavior and both
  developers accept the proposed authorization/audit ADRs before promotion to
  `dev`.
- Data: repository content and synthetic identities/alerts only. No secrets,
  real client data, external providers, or AWS services were used.
- Verification: the full 92-test Maven reactor build passed with no failures or
  errors, including disposable PostgreSQL 16 Testcontainers coverage.

## 2026-09-28 — EDA-D1-03 offline ingestion core

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: provider-neutral public-search contracts, grouped-query controls,
  watermark and lease persistence, canonical URL governance and deduplication,
  bounded retries, durable partial-run replay accounting, AgentCore adapter
  boundary, worker-mode guard, tests, and documentation.
- Human verification required: review the provider contract and source controls;
  accept the proposed processing/search/content-governance ADRs; supply the
  separately approved IAM/Gateway implementation only after EDA-D1-02 clears.
- Data: repository content and synthetic public-market terms only. No credentials,
  client data, external provider calls, or AWS services were used.
- Verification: the full Maven build passed with 88 tests, including retry,
  deduplication, durable replay accounting, and PostgreSQL 16 Testcontainers
  schema validation. Live AgentCore behavior, latency, cost, and IAM enforcement
  are not claimed.

## 2026-09-30 — EDA-D1-03 bounded ingestion replay

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: explicit historical replay windows, worker-mode isolation, future/span
  validation, idempotent run reuse, durable result accounting, watermark safety,
  tests, and documentation.
- Human verification required: review the replay span and connect this internal
  boundary only through the future authenticated, audited operator workflow.
- Data: repository content and synthetic public-market terms only. No secrets,
  real client data, provider calls, or AWS services were used.
- Verification: the full Maven build passed with 92 tests, including bounded,
  future-window, worker-mode, durable-count, and watermark-regression cases plus
  PostgreSQL 16 Testcontainers validation.

## 2026-09-29 — EDA-D1-04 offline AI safety layer

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: public-only classifier and briefing contracts, model kill switch,
  prompt-injection/private-field screening, strict structured-output validation,
  citation/instrument enforcement, retry/timeout/token caps, deterministic
  rule-review fallback, synthetic evaluation fixture, metric harness, tests, and
  documentation.
- Human verification required: accept ADR-0010, review safety patterns and
  candidate benchmark gates, expand and label the held-out fixture set, and run
  the live benchmark only through an approved provider connection.
- Data: repository content and synthetic public-market examples only. No secrets,
  real client data, model provider calls, or AWS services were used.
- Limitation: offline tests verify contracts and failure behavior, not Luna model
  quality, latency, tokens, cost, regional routing, or provider availability.

## 2026-09-29 — EDA-D1-04 Bedrock Runtime adapter

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: official-contract review, pinned AWS SDK integration, default-off Bedrock
  Runtime Converse adapter, global Luna inference-profile configuration, bounded
  timeouts/tokens, fail-closed model allowlisting, safe provider-error mapping,
  mocked tests, and documentation.
- Human verification required: accept ADR-0010, review the IAM policy and global
  cross-region data boundary, then run the held-out benchmark only through the
  approved lower-environment connection.
- Data: repository content and synthetic public JSON only. No credentials, real
  client data, model requests, or AWS service calls were used.
- Verification: mocked adapter tests exercise request construction, disabled and
  unapproved-model rejection, timeout propagation, and retryable throttling. Live
  IAM, routing, quality, latency, tokens, and cost remain unverified.

## 2026-09-29 — EDA-D1-04 offline benchmark reporting

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: provider usage-metadata contract, Bedrock token/latency/stop-reason capture,
  strict benchmark-run schema, complete fixture coverage checks, p95 latency and
  token aggregation, candidate quality/safety gates, JSON report entry point,
  tests, and evaluation documentation.
- Human verification required: review fixture labels and candidate thresholds,
  record the approved model price used for estimated cost, inspect every safety
  result, and accept ADR-0010 before enabling the provider.
- Data: repository content and synthetic benchmark observations only. No model,
  AWS, credential, or private-data access occurred.
- Verification: focused tests cover metadata capture, passing/failing gates,
  complete-run enforcement, version propagation, cost recording, and p95 latency.
  No live benchmark result is claimed.

## 2026-09-29 — EDA-D1-04 synthetic fixture expansion

- Human owner: Azeez; Hayyun review remains required before labels are accepted.
- Tool: OpenAI Codex.
- Use: expand the public-only evaluation fixture from five to eighteen synthetic
  cases, add full event-type and negative/safety coverage, enforce unique IDs and
  synthetic identifiers, verify expected safety-screen outcomes, and prevent an
  AI-draft fixture from qualifying as model-enablement evidence.
- Human verification required: Azeez and Hayyun inspect every expected event type,
  negative label, and safety outcome before changing `labelStatus` to
  `HUMAN_REVIEWED` or using results for an enablement decision.
- Data: invented issuer/news examples and synthetic identifiers only. No real
  market article, client data, credentials, model call, or AWS service was used.
- Verification: 54 non-Docker service tests, including fixture-integrity and
  report-eligibility tests, pass locally. The full rerun could not start its two
  PostgreSQL Testcontainers classes because Docker Desktop was unavailable; the
  preceding full build passed 74 tests before this fixture-only change. No model
  quality, latency, routing, token, or cost result is claimed.

## 2026-09-29 — EDA-D1-05 offline operations controls

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: provider-neutral default-off kill switches, hard search/token/budget
  limits, low-cardinality metrics, durable redacted audit-outbox persistence for
  re-enable, cost-control documentation, and an offline operations runbook.
- Human verification required: accept ADR-0014, review limits and planning cost
  assumptions, integrate provider/operator/audit adapters, and validate AWS
  billing, IaC, alarms, and an operations drill before cloud enablement.
- Data: repository content and synthetic test values only. No secrets, real
  client data, external providers, or AWS services were used.
- Verification: the full Maven build passed with 72 tests, including PostgreSQL
  Testcontainers coverage for durable re-enable audit and outbox-capacity failure.

## 2026-09-28 — EDA-D2-05 event-alert dashboard

- Human owner: Azeez; Hayyun review remains required for the CRM/frontend scope.
- Tool: OpenAI Codex.
- Use: active frontend discovery, React alert queue/detail implementation,
  evidence and explanation presentation, agent decisions and feedback,
  read-only administrator oversight, responsive state design, API adapter,
  synthetic fixtures, tests, and documentation.
- Human verification required: Hayyun reviews the frontend integration and a
  developer completes the visual desktop/mobile pass before promotion to
  `feat/event-driven-alerts`.
- Data: repository content and synthetic client, exposure, alert, and public
  citation examples only. No secrets, real client data, external providers, or
  AWS services were used.
- Verification: frontend unit/interaction tests, ESLint, production build, and
  static UI-pattern audit were run locally. Automated screenshot review was not
  available because no controllable browser was exposed to the session.

## 2026-09-30 — EDA-D2-05 dashboard workflow hardening

- Human owner: Azeez; Hayyun review remains required for the CRM/frontend scope.
- Tool: OpenAI Codex.
- Use: latest-decision presentation, immediate status synchronization after a
  mutation, terminal-state decision guards, persistent feedback access, styling,
  tests, and documentation.
- Human verification required: Hayyun reviews the workflow and a developer
  completes the desktop/mobile visual pass before feature-branch integration.
- Data: repository content and synthetic alert/decision values only. No secrets,
  real client data, external services, or AWS resources were used.
- Verification: nine frontend unit/interaction tests, ESLint, and the production
  Vite build passed. Automated screenshots remain pending because the local
  headless browser could not establish its Chrome CDP channel after diagnostics
  and restart.

## 2026-09-30 — EDA-D2-05 authoritative decision-state handling

- Human owner: Azeez; Hayyun review remains required for the CRM experience.
- Tool: OpenAI Codex.
- Use: aligned the synthetic decision adapter with the expanded API response and
  changed the dashboard to render the audited server-supplied `newStatus` rather
  than independently reconstructing the lifecycle transition.
- Human verification required: Hayyun reviews the interaction and completes the
  desktop/mobile visual pass before promotion to `dev`.
- Data: repository content and synthetic alerts only. No secrets, real client
  data, external providers, or AWS services were used.
- Verification: all nine frontend tests passed, ESLint completed without errors,
  and the Vite production build succeeded.

## 2026-09-30 — EDA-D2-06 offline integration and lifecycle controls

- Human owner: Azeez; Hayyun review remains required for shared domain and
  experience scope.
- Tool: OpenAI Codex.
- Use: integrate the API, ingestion, AI, operations, and dashboard task streams;
  implement the source-to-alert persistence path, operational quota wiring,
  leased Kafka audit publication, strict auditor allowlisting, bounded retention,
  PostgreSQL end-to-end coverage, and desktop/mobile browser verification.
- Human verification required: Hayyun reviews the integrated behavior and the
  proposed ADRs; Azeez reviews the synthetic AI labels before any feature-to-dev
  PR or provider enablement.
- Data: repository content and synthetic fixtures only. No credentials, real
  client data, AWS endpoint, model provider, or production broker was used.
- Verification: focused Java/PostgreSQL tests and the Go auditor contract test
  passed; the full clean Maven/frontend verification is recorded in the offline
  integration evidence after completion. Desktop and mobile synthetic dashboard
  screenshots were inspected locally.

## 2026-09-30 — EDA-D2-06 offline delivery and recovery closure

- Human owner: Azeez; Hayyun review remains required for shared auditor and
  domain behavior.
- Tool: OpenAI Codex.
- Use: add an embedded-Kafka producer/consumer acknowledgement test, verify that
  a failed event does not stop its publisher batch, exercise expired lease
  recovery and the retry cap against PostgreSQL, and add the Go auditor contract
  suite to CI with a disposable healthy database.
- Human verification required: Hayyun reviews the cross-service contract and
  proposed ADRs before feature promotion; the authenticated operator role
  contract remains a human design decision.
- Data: repository content and synthetic UUIDs/payloads only. No credentials,
  real client data, AWS endpoint, production broker, or external model was used.
- Verification: the clean Java reactor, disposable Go/PostgreSQL auditor tests,
  frontend tests/lint/build, Compose validation, and diff checks are recorded in
  the offline integration evidence.

## 2026-09-30 — Holdings-derived public search universe

- Human owner: Azeez; Hayyun review remains required for holdings/domain scope.
- Tool: OpenAI Codex.
- Use: replace the static active-instrument search universe with a Singapore-side
  aggregate derived from synthetic client holdings; map stable instrument IDs to
  public ticker, issuer, and sector terms; add minimum client-count/share privacy
  controls, synthetic reference migration, tests, ADR, and documentation.
- Human verification required: review ADR-0016, the 5%/two-client prototype
  thresholds, selection-inference risk, and the real instrument-master boundary
  before AWS enablement or promotion to `dev`.
- Data: repository-owned synthetic holdings and public synthetic instrument
  metadata only. No credentials, real clients, AWS endpoint, or external model
  was used.
- Verification: focused selection/query tests and the full local verification
  suite are recorded in the offline integration evidence.

## 2026-09-30 — Stakeholder and ASR report documentation

- Human owner: Azeez; team review is required before proposal/final-report use.
- Tool: OpenAI Codex.
- Use: draft a Solution View that introduces referenced AWS services, distinguish
  application users from IAM identities, document stakeholder permissions, and
  write Admin, Agent, and Event-Driven Client Alert ASRs with main/alternative
  flows, pre/post-conditions, architectural significance, and Mermaid sequences.
- Human verification required: confirm the Admin/Agent core-feature behavior with
  the relevant owners; confirm AWS service names and deployed evidence with the
  platform owner; accept the proposed alert ADRs before presenting planned
  controls as implemented.
- Data: repository architecture decisions, plans, NFR notes, and synthetic-system
  descriptions only. No credentials, real client data, AWS connection, external
  model call, or production evidence was used.
- Verification: cross-checked terminology against ADR-0004 and proposed
  ADR-0006 through ADR-0016; checked Markdown structure, Mermaid participants,
  regional privacy boundaries, and explicit separation of current MVP behavior
  from future vector-similarity work.

## 2026-10-16 — Client Health SF01 transaction intake decision

- Human owner: Garvit; Feature 3 owners must review provider-side changes.
- Tool: Cursor.
- Use: inspect the current transaction-service contracts and accepted identity,
  ingestion and database decisions; select a bounded service-authenticated pull
  API for six-month replay and daily Client Health intake; update ADR-0044, the
  feature plan and contract documentation.
- Human verification required: Feature 3 owners approve the dedicated replay
  endpoint, stable synthetic source fingerprint and six-month provider fixtures
  before their shared service files change.
- Data: repository documentation and synthetic contract examples only. No
  credentials, real client data, AWS calls or external model calls were used.
- Verification: synchronized `feat/client-health-monitor` through PR #111,
  reviewed ADR-0024, ADR-0025, ADR-0034, ADR-0037 and ADR-0043, and ran Git
  whitespace/diff checks.

## 2026-10-16 — Client Health SF01 consumer foundation

- Human owner: Garvit.
- Tool: Cursor.
- Use: implement provider-independent matched-transaction records, bounded
  cursor replay, complete-run validation, de-duplication and failure-path tests.
- Human verification required: review contract fields, replay limits and the
  fail-closed behavior before committing; Feature 3 provider changes remain
  separately owned.
- Data: synthetic identifiers and transaction examples only. No credentials,
  real client data, AWS calls or external services were used.
- Verification: 27 client-health module tests passed with the local Java 24
  compiler override; CI must repeat them on the required Java 25 toolchain.

## 2026-10-21 — Client Health architecture acceptance and SF01 mock replay

- Human owner: Garvit.
- Tool: Cursor.
- Use: revise and accept ADR-0034 after owner review; add the transaction replay
  page schema and unsafe fixtures; implement a contract-faithful six-month
  synthetic provider covering all three planned signals and controls.
- Human verification required: Swayam reviews the accepted architecture before
  building SF05-SF07; Joseph Yau/Neo Jia Wen review and implement the Feature 3
  provider side before production enablement.
- Data: repository-owned synthetic identifiers and transactions only. No
  credentials, real client data, AWS calls or external services were used.
- Verification: all 32 client-health tests passed with the local Java 24
  compiler override, including contract/schema/mock coverage; CI must repeat
  them on Java 25. Lint diagnostics and Git whitespace checks passed.

## 2026-10-21 — Client Health SF02 activity projection and baselines

- Human owner: Garvit.
- Tool: Cursor.
- Use: add explicit activity classification, atomic/idempotent transaction
  projection, replay-coverage tracking, and six-month/90-day/30-day account and
  client baseline summaries for the future deterministic rules.
- Human verification required: review ADR-0051, the 08:00 Asia/Singapore window
  boundaries, SGD-only failure behavior, and coordinate production health-db
  migration ownership with Swayam before a database adapter is added.
- Data: repository-owned synthetic transactions only. No credentials, real
  client data, AWS calls or external services were used.
- Verification: all 39 client-health tests passed with the local Java 24
  compiler override, including projection, status-transition, rollback,
  classification, currency, coverage, roll-up and synthetic baseline coverage;
  Java 25 CI verification remains required.

## 2026-10-21 — Client Health SF03 deterministic signal rules

- Human owner: Garvit.
- Tool: Cursor.
- Use: record ADR-0052; add rolling 30-day period summaries; implement the
  versioned recurring-credit interruption, customer-activity decline and
  sustained-net-outflow rules plus typed outcomes for RH-SF04.
- Human verification required: review project/demo thresholds and severity
  bands before commit; they are not universal banking standards. Java 25 CI
  must repeat local verification.
- Data: repository-owned synthetic baseline scenarios only. No credentials,
  real client data, AWS calls, Bedrock calls or external services were used.
- Verification: all 42 client-health tests passed with the local Java 24
  compiler override, covering positive signals, exact low-severity boundaries,
  healthy controls, just-below thresholds, insufficient/Unknown activity and
  deterministic repeatability; Java 25 CI remains required.

## 2026-10-21 — Client Health SF04 analysis orchestration

- Human owner: Garvit.
- Tool: Cursor.
- Use: record ADR-0053; implement renewable run-lease/watermark ports,
  deterministic findings/signals/fingerprints, one-finding-per-client grouping,
  retry-safe task handoff, and explicit cooldown/cap suppression outcomes.
- Human verification required: Swayam reviews and implements task-side atomic
  admission in RH-SF05; production run persistence and Java 25 CI remain
  required.
- Data: repository-owned synthetic transactions/findings only. No credentials,
  real client data, AWS calls, Bedrock calls or external services were used.
- Verification: all 47 client-health tests passed with the local Java 24
  compiler override, including success, repeated-run, lease-contention,
  suppression, partial-failure and deterministic-retry scenarios.

## 2026-10-21 — Client Health offline rule evaluation

- Human owner: Garvit.
- Tool: Cursor.
- Use: build a deterministic labelled-scenario harness for
  `relationship-health-v1`, covering positive, healthy-control, exact-boundary,
  just-below, Unknown-classification and sparse-history cases; render and
  document fixed-set rule metrics and limitations.
- Human verification required: treat 1.00 precision/coverage only as agreement
  with six repository-owned synthetic scenario groups, never as real-client or
  production accuracy; threshold changes require a new ruleset version.
- Data: repository-owned synthetic aggregate histories only. No credentials,
  real client data, AWS calls, Bedrock calls or external services were used.
- Verification: all six scenario groups and repeated evaluations passed; all 49
  client-health tests passed with the local Java 24 compiler override. Java 25
  CI remains required.

## 2026-10-22 — Client Health SF08 audit/outbox foundation

- Human owner: Garvit; Auditor-owner review remains required for shared files.
- Tool: Cursor.
- Use: implement a deterministic redacted relationship-health audit envelope,
  task/suppression/run event mapping, transactional outbox and delivery ports,
  and a bounded leased relay with idempotent enqueue semantics and redacted
  retry categories.
- Human verification required: do not add or deploy the Flyway/JDBC outbox,
  SQS gateway, IAM policy or Auditor allowlist until SF05 V1 is corrected and
  the Auditor owner accepts the service/action/payload contract.
- Data: repository-owned synthetic opaque identifiers and task states only. No
  credentials, real client data, AWS calls, SQS calls or external services were
  used.
- Verification: deterministic IDs, client-reference hashing, note exclusion,
  suppression mapping, duplicate enqueue, continue-after-failure and retry
  release behavior passed; all 54 Client Health tests passed locally with the
  Java 24 compiler override. Java 25 CI remains required.

## 2026-10-22 — Client Health SF08 V3 and atomic task enqueue

- Human owner: Garvit; Auditor-owner review remains required for SQS/Auditor
  adapters.
- Tool: Cursor.
- Use: add the V3 relationship-health outbox schema and indexes; extend the
  SF05 transaction session with deterministic audit enqueue; emit redacted
  task-created/evidence/transition and suppression events atomically with task
  and processed-finding changes.
- Human verification required: production JDBC must use one transaction for
  task/finding/outbox writes; SQS/IAM and Auditor allowlist changes remain
  blocked on owner acceptance.
- Data: repository-owned synthetic opaque identifiers and task states only. No
  credentials, real client data, AWS calls, SQS calls or external services were
  used.
- Verification: task audit counts, deterministic mapping, suppression enqueue,
  processed-finding rollback, outbox contract constraints and the full Client
  Health suite are required before commit.

## 2026-10-22 — Client Health SF05 persistence review fixes

- Human owner: Garvit; original V1 schema/admission implementation by Swayam.
- Tool: Cursor.
- Use: review merged PR #131 against ADR-0053 and the frozen evidence contract;
  add an immutable V2 migration for strict non-overlapping windows, supported
  signal types, SHA-256 fingerprints and closed-task cooldown indexing; align
  migration tests and database/service/feature documentation.
- Human verification required: do not rewrite merged V1; production JDBC
  repository and SF08 atomic outbox integration remain separate work.
- Data: repository schema and synthetic PostgreSQL test rows only. No
  credentials, real client data, AWS calls or external services were used.
- Verification: 57 Client Health tests compiled and completed locally with the
  Java 24 override; 51 passed and six Testcontainers migration tests were
  skipped because local Docker was unavailable. Java 25 CI with Docker must run
  V1-to-V2 migration, rerun idempotency, valid evidence and malformed
  window/type/fingerprint cases before merge.

## 2026-10-23 — Client Health SF06 runtime and assignment hardening

- Human owner: Swayam.
- Tool: OpenAI Codex.
- Use: implement Spring runtime wiring, the PostgreSQL task/query adapter,
  Contract-B client-credentials consumer, current-assignment authorization,
  bounded disposition notes, API schema updates, tests and startup guidance.
- Human verification required: review the SQL mapping and authorization flow;
  the client-profile owner must implement and approve Contract B and scoped
  Cognito provisioning before live assignment lookups are enabled.
- Data: repository code and synthetic identifiers only. No credentials, real
  client data, AWS calls or external services were used.
- Verification: focused domain/controller/adapter/configuration tests and the
  full Client Health Maven suite; PostgreSQL Testcontainers coverage depends on
  local Docker availability.

## 2026-10-23 — Client Health SF06 reassignment, pagination and PostgreSQL completion

- Human owner: Swayam.
- Tool: OpenAI Codex.
- Use: remove historical-assignment pre-filtering, enforce current assignment
  for Agent reads and actions, replace numeric OFFSET cursors with opaque
  Base64URL keyset cursors, integrate SF08 audit enqueue into the JDBC task
  transaction, and add PostgreSQL and full Spring runtime integration tests.
- Human verification required: run the Testcontainers suites in Docker-enabled
  CI and review the keyset behavior when rows move ahead of an issued cursor;
  the client-profile Contract-B provider and Cognito provisioning remain owned
  outside this branch.
- Data: synthetic opaque clients, Agents, tasks, findings and audit events only.
  No real client data, secrets, AWS calls or live services were used.
- Verification: application-level reassignment and paging tests pass; the full
  Maven suite reports 108 tests with no failures and 14 Docker-dependent tests
  skipped locally because Docker is unavailable.

## 2026-10-26 — Client Health SF02/SF04 production persistence

- Human owner: Garvit.
- Tool: Cursor.
- Use: add V4 activity-projection/replay-coverage/analysis-run tables, a
  transactional JDBC activity adapter, a fenced renewable lease/watermark
  adapter, Spring runtime wiring, and PostgreSQL integration coverage.
- Human verification required: Java 25 CI with Docker must execute V1-V4 and
  the new activity/run Testcontainers suite; the migration is additive and
  previously merged migrations remain unchanged.
- Data: repository-owned synthetic opaque transactions and analysis-run IDs
  only. No credentials, real client data, AWS calls or external services were
  used.
- Verification: all source and tests compile with the local Java 24 override;
  94 non-Docker tests passed and 17 Docker-dependent tests were skipped because
  local Docker was unavailable.

## 2026-10-26 — Client Health SF07 safe explanation and fallback

- Human owner: Swayam.
- Tool: OpenAI Codex.
- Use: implement identifier-free derived-fact mapping, strict model-output
  validation, deterministic templates, a bounded Bedrock Converse adapter and
  kill switch, versioned explanation persistence, atomic redacted audit enqueue,
  and Contract-D response attachment.
- Human verification required: select and approve a Singapore-supported model,
  token pricing, IAM permissions and environment rollout before enabling
  Bedrock; CloudWatch publication remains in RH-SF09.
- Data: repository-owned synthetic task and signal facts only. No credentials,
  real client data, live AWS calls, raw transactions or external services were
  used.
- Verification: Java 25 unit, PostgreSQL migration/repository, rollback and
  full Spring runtime tests; Docker-dependent coverage runs when Docker is
  available.

## 2026-10-26 — Client Health SF08 audit delivery

- Human owner: Garvit; Auditor-owner and infrastructure reviews remain required.
- Tool: Cursor.
- Use: implement JDBC outbox leasing/fencing/recovery, bounded retry release,
  snake_case SQS serialization, property-gated AWS/LocalStack configuration,
  scheduled relay wiring, and atomic signal/analysis-run audit events.
- Human verification required: Java 25 CI with Docker must execute the outbox
  and activity/run PostgreSQL suites; Hayyun must review Auditor validation;
  Neo must review task-role IAM and Terraform environment wiring.
- Data: repository-owned synthetic opaque IDs and task/run states only. No
  credentials, real client data, AWS calls or external services were used.
- Verification: 117 tests compiled with the local Java 24 override; 97
  non-Docker tests passed and 20 Docker-dependent tests were skipped because
  local Docker was unavailable. Unit coverage includes redacted SQS envelopes,
  provider error classification, signal/run events, retry and lease fencing.

## 2026-10-26 — Client Health SF07 safety review fixes

- Human owner: Garvit; original SF07 implementation by Swayam.
- Tool: Cursor.
- Use: repair missing explanations when deterministic task retries return
  `DUPLICATE`; tighten model-output validation to reject all numeric wording and
  prevent sign/context recombination of otherwise allowed input values; update
  Bedrock instructions and regression coverage.
- Human verification required: keep live Bedrock disabled until model, pricing,
  IAM and environment rollout are approved; Java 25 CI with Docker must pass
  before feature promotion.
- Data: repository-owned synthetic evidence only. No credentials, real client
  data, AWS calls, Bedrock calls or external services were used.
- Verification: regression tests cover explanation-persistence failure followed
  by duplicate retry, no repeated generation after repair, and rejection of
  invented, sign-reversed and wrong-context numeric wording. The local Java 24
  suite completed 129 tests with no failures: 106 passed and 23
  Docker-dependent tests were skipped; Java 25 CI with Docker remains required.

## 2026-10-27 — Client Health SF09 metrics and health

- Human owner: Swayam; reviewer: Garvit; later infrastructure review: Neo.
- Tool: OpenAI Codex.
- Use: add bounded Micrometer instrumentation across Client Health processing,
  anonymous liveness/database readiness probes, commit-aware audit metrics,
  backlog gauges and operational documentation.
- Human verification required: Java 25 Docker CI must execute PostgreSQL
  readiness and repository tests; RH-SF11 must review CloudWatch export, alarm
  thresholds, SNS ownership, IAM and cost before deployment.
- Data: repository-owned synthetic opaque identifiers only. No credentials,
  real client data, live AWS calls, Bedrock calls, SQS calls or external services
  were used.
- Verification: SimpleMeterRegistry unit tests, Spring security/health tests,
  PostgreSQL rollback/recovery tests and the complete Client Health Maven suite.

## 2026-10-27 — Client Health SF10 API-backed UI

- Human owner: Garvit; shared frontend owner review remains required.
- Tool: Cursor.
- Use: replace seeded Client Health state with authenticated Contract-D list,
  detail, history and action calls; add Agent actions, Admin read-only oversight,
  filters, cursor pagination, explanation source, resilient API states and
  frontend tests.
- Human verification required: review shared navigation/auth changes and verify
  the screen against a deployed Client Health API before promotion.
- Data: repository-owned synthetic API fixtures only. No credentials, real
  client data, AWS calls or external services were used.
- Verification: frontend API/page/router/navigation tests, ESLint and the Vite
  production build passed; all 139 frontend tests across 20 files passed and
  the focused Client Health API/page tests passed independently. Vite retained
  the existing large-chunk warning.

- 2026-10-22 — Joseph Yau — Codex — Added client filtering to the authenticated, paged auditor activity query and corresponding user-service query parameters. Files: `proto/audit/v1/audit.proto`, auditor activity controller/model/repository and tests, user-service activity controller/service/record and tests, API contract, proposed ADR, and auditor feature plan. Protobuf generation, all auditor tests, SQL-backed activity query tests, focused user-service tests, and formatting checks passed; proposed ADR-0042 still needs human acceptance/review.

## 2026-10-23 — Admins read-only on client data (open item 23)

- Human owner: Neo Jia Wen (decision owner; accepted ADR-0059).
- Tool: Claude Code (Anthropic).
- Use: draft ADR-0059 from the requirements Q&A (UM01, UM04, UM18, CPM24,
  CPM31); add a `RequireAgent` route check to client-service and account-service
  so admins get 403 on every client and account write; make account-service's
  `CreateAccount` refuse admins; make the client page read-only for admins;
  update tests, READMEs, the Feature 2 plan, the security model and open items
  23 and 24. Client reassignment (open item 24) is left for later.
- Human verification required: confirm that admins
  losing every client write is acceptable until reassignment exists; run the
  end-to-end checks in ADR-0059's Validation section.
- Data: repository content and synthetic test data only. No credentials, real
  client data or AWS resources were used.
- Verification: `make test` passed for account-service and client-service
  (Docker, with PostgreSQL); frontend lint, 204 tests and build passed (one
  test failed once while Docker tests ran alongside and passed on rerun). Not
  run end to end.

## 2026-10-23 — Client reassignment (open item 24)

- Human owner: Neo Jia Wen (decision owner; accepted ADR-0060).
- Tool: Claude Code (Anthropic).
- Use: draft ADR-0060 from the requirements Q&A (UM07, UM15, UM17, UM18,
  CPM30); rename `clients.created_by_agent_id` to `assigned_agent_id`; add
  `POST /api/clients/reassignments` to client-service (user lookup in
  user-service, account move through account-service's new
  `PUT /ownership/accounts`, one audit event per client); add Reassign controls
  to Admin Home and the client page; wire `USER_SERVICE_BASE_URL` and a
  security-group rule in `stacks/crm`; update tests and docs. Also renumbered
  the duplicate F2-SEC-02 task added with ADR-0059 to F2-SEC-04.
- Human verification required: decide what
  client-health-monitor-service does with open tasks of a moved client; run
  `terraform plan` for `stacks/crm` and the end-to-end check in ADR-0060.
- Data: repository content and synthetic test data only. No credentials, real
  client data or AWS resources were used.
- Verification: `make test` passed for client-service and account-service
  (Docker, with PostgreSQL), and the reassignment repository tests were run
  again verbosely; frontend lint and the affected test files passed;
  `terraform fmt -check` passed. Terraform was not planned or applied. Not run
  end to end.

## 2026-10-24 — CodeQL and dependency scanning in CI (ADR-0028)

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: add CodeQL (Java, Go, JavaScript/TypeScript, Python, Actions) and
  OSV-Scanner to `.github/workflows/ci.yml`. The repository has no GitHub
  Code Security, so CodeQL results are not uploaded: the job fails on any
  alert and keeps the SARIF report as an artifact. OSV-Scanner fails on any
  known vulnerability in a dependency except the three accepted, each with a
  reason and an expiry date, in `osv-scanner.toml`: two `spring-webmvc`
  advisories for features no service uses (`XsltView`, server-sent event
  fragments), fixed only in Spring Boot 4, and `x/crypto/openpgp`, which no
  service imports and no release fixes. Go call analysis and OSV's Python
  resolver are turned off (see the comments in the workflow). One CodeQL rule
  (`java/spring-disabled-csrf-protection`) is excluded in
  `.github/codeql/codeql-config.yml`, because every Java service is a
  stateless bearer-token API.
- Human verification required: confirm with the course that running CodeQL
  on this private repository is allowed under its licence; review the
  accepted vulnerabilities before they expire on 2027-01-31. The 276 known
  vulnerabilities OSV-Scanner reported in existing dependencies are upgraded
  in the next entry.
- Data: repository content only. No credentials, real client data or AWS
  resources were used.
- Verification: CodeQL CLI 2.27.3 run locally on a clean export of `dev`:
  Java (Maven build), JavaScript/TypeScript and Python had no alerts once the
  CSRF rule was excluded. Go and Actions were not run locally (Go code
  generation needs Docker; the Actions extractor failed on Windows).
  OSV-Scanner v2.6.0 run locally with the workflow's arguments on this
  branch: exit 0 with `osv-scanner.toml`, exit 1 without it. actionlint
  passed. The workflow has not run on GitHub yet.

## 2026-10-24 — Security upgrades for dependencies

- Human owner: Neo Jia Wen. The owners of each changed service should review.
- Tool: Claude Code (Anthropic).
- Use: upgrade the dependencies OSV-Scanner reported as vulnerable, except
  `spring-webmvc`, which is fixed only in Spring Boot 4. Go: `golang.org/x/crypto`
  0.57.0, `x/net` 0.60.0, `x/sys` 0.48.0, `x/text` 0.42.0 and `quic-go` 0.59.1 in
  account-service, client-service and the SFTP receiver; the same `x/*`
  versions and gRPC 1.83.2 in the auditor. Java: the root `pom.xml` overrides
  the versions Spring Boot 3.5.16 manages for Jackson, Netty, Log4j, Tomcat,
  the PostgreSQL driver, commons-lang3, commons-compress and HttpComponents;
  user-service moves to gRPC 1.84.2 and protobuf 3.25.9. Frontend: Vite
  8.3.4 and Vitest 5.0.3, with zod 4.6.7 and undici 7.30.1 updated in the
  lockfile (build and test tools only). Search spike: every package pinned,
  with `urllib3` at 2.8.0. Each version is the newest patch release at least two weeks old,
  except zod and undici, whose only fixed releases are newer.
- Human verification required: review by the owners of the changed services.
- Data: repository content only. No credentials, real client data or AWS
  resources were used.
- Verification: `mvn -B verify` passed (491 tests across the six Java modules,
  Testcontainers on Docker); `make test` passed for the auditor,
  account-service, client-service and SFTP; frontend lint, 210 tests and
  build passed; the search spike's 9 unit tests passed. OSV-Scanner v2.6.0
  then reported none beyond the three accepted in `osv-scanner.toml` (see
  the previous entry), down from 276. Deployed the upgraded
  services and confirmed every service runs as expected.

## 2026-10-24 — Dashboard account figures per currency

- Human owner: Neo Jia Wen. Joseph Yau should review the account-service
  change (ADR-0062 names him for agreement).
- Tool: Claude Code (Anthropic).
- Use: draft ADR-0062 and implement it. account-service no longer refuses
  account figures (HTTP 409) when the window's accounts use more than one
  currency: it sums booked initial deposits per currency, in total and per
  agent, and leaves each day's value out. dashboard-service passes the
  per-currency amounts through, ranks the per-agent table's deposits in a
  `currency` parameter (default SGD), caches each currency apart, and drops
  the `mixed_currencies` panel reason. The frontend shows each currency's
  amount, labels the transaction value with its currency (or lists the
  currencies when there are several), and offers a currency choice for the
  deposits ranking. The contracts, feature plan, READMEs, demo script and
  comments that assumed one currency were updated.
- Human verification required: review of ADR-0062 and the changed services
  by their owners; a check on the deployed stack with an SGD and a USD account.
- Data: repository content only, plus the project specification and the
  Q&A spreadsheet in `references/`, searched for currency rules. No
  credentials, real client data or AWS resources were used.
- Verification: account-service `make test` passed, including new
  repository tests with two currencies; dashboard-service `mvn test` passed
  (108 tests); frontend lint, 243 tests and build passed; SFTP producer tests
  passed; Redocly CLI 1.34.5 found both dashboard specs valid, with the same
  two warnings as before. Not yet run end to end.

## 2026-10-24 — OpenAPI contract tests (ADR-0063)

- Human owner: Neo Jia Wen. The owners of user-service, client-profile-
  management-service, account-service, the SFTP receiver and the Auditor
  should review the specs written for their services.
- Tool: Claude Code (Anthropic).
- Use: draft ADR-0063 and implement it. Wrote OpenAPI 3.0.3 specs from the
  code for users, clients, accounts, transactions and corrupted logs, and
  moved the alerts and activity specs from 3.1 to 3.0.3. Added a contract
  check to every service's tests: Java services check every MockMvc response
  (swagger-request-validator) and Go services check controller responses
  (kin-openapi); route tests fail on any route missing from a contract, and
  dashboard-service and engagement-service check their canned upstream
  replies against the provider's contract. Added a CI job that validates the
  specs and runs `oasdiff breaking` on pull requests, converting a 3.1 base
  copy to 3.0 first, with deliberate breaking changes listed in
  `docs/api/accepted-breaking-changes.txt`. Fixed what the checks
  found where the code was clearly wrong (four Go handlers sent JSON without
  a JSON content type; the Auditor's plain-text errors reached admins as 500s
  through user-service, so they are now JSON; account-service's PATCH
  answered the status in capitals, unlike GET), corrected the existing specs where they were
  (undocumented error bodies and statuses, the RFC 9457 `instance` field, a
  payload that is not always an object), made test fixtures realistic, and
  gave account-service's security tests their own account IDs, which had
  collided with a repository test's.
- Human verification required: review of ADR-0063 and of each new spec by
  its service's owner; a check that the CI job passes on the pull request.
- Data: repository content only. No credentials, real client data or AWS
  resources were used.
- Verification: `mvn verify` passed for every Java module; `make test` passed
  for account-service, client-profile-management-service, the SFTP receiver
  and the Auditor; every spec passed openapi-spec-validator 0.9.0; oasdiff
  1.33.0 found one breaking change against `dev`, the accepted activity
  `payload` one; breaking a spec on purpose failed the expected tests; OSV-Scanner
  2.6.0 found no new vulnerability once Rhino was pinned; actionlint and
  shellcheck passed.

## 2026-10-24 — Business dashboard timing out on first load

- Human owner: Neo Jia Wen. The user-service owner should review the
  user-service change.
- Tool: Claude Code (Anthropic).
- Use: find why the business dashboard's transactions panel timed out on
  first load although the budgets had been raised to 10 s and 15 s. With the
  owner running each test in AWS: a first guess (cold start in user-service)
  and a second (stale connections after a deploy) were ruled out by
  CloudWatch logs; temporary Service Connect access logs and JDK HttpClient
  request logging then showed the SFTP receiver answering in under 50 ms
  while user-service never finished reading the response, on the first call
  after about 30 s idle, with no deploy needed. Switching user-service to
  `HttpURLConnection` through an environment variable made the panel load
  after every idle pause. Made that permanent in user-service's
  `application.yml` with 2 s connect and 3 s read timeouts. Returned
  dashboard-service's budgets to 2 s and 3 s at the owner's request; after
  the transactions panel timed out on the first load after a restart, set
  them to 5 s and 8 s, as the owner chose. The panel still timed out after
  an idle pause; temporary Tomcat access logging in user-service and JDK
  HttpClient logging in dashboard-service showed user-service answering in
  under 220 ms while dashboard-service's JDK client got the headers and
  never finished the chunked HTTP/1.1 body. That disproved the first
  explanation recorded (the JDK client's h2c upgrade), since this call is
  HTTPS. Switched dashboard-service's client to `HttpURLConnection` too.
  Amended ADR-0033, and updated both READMEs and the feature plan. All
  temporary Terraform changes were removed before committing.
- Human verification required: review by the user-service owner; after
  deploying user-service and dashboard-service, load the dashboard after a
  2-minute pause several times, and once just after a restart.
- Data: CloudWatch logs and ECS events from the team's AWS account, read by
  the owner and pasted in; synthetic test data. No credentials or real
  client data were shared.
- Verification: user-service `mvn test` passed (87 tests) and
  dashboard-service `mvn test` passed (109 tests). The user-service change
  is deployed and its stall no longer appears; the dashboard-service change
  is not yet deployed.

## 2026-10-27 — Client Health transaction history API update

- Human owner: Joseph Yau. Client Health contract, security and transaction
  owners should review the proposed ADR and API changes.
- Tool: Codex (OpenAI).
- Use: remove the required client filter from the transaction history route,
  query all matched clients through SQLBoiler, add keyset pagination and
  service-token route coverage, move Client Health schema additions out of
  `init.sql` and keep them in V2, constrain source fingerprints, add three
  synthetic signal cases plus healthy controls, update the OpenAPI contract,
  and rename the proposed history ADR to ADR-0065.
- Human verification required: review the revised response/query contract,
  proposed ADR-0065, fingerprint rule, and synthetic cases; run a real
  Cognito/import/read integration check before deployment.
- Data: repository content and synthetic fixtures only. No credentials, real
  client data or AWS resources were used.
- Verification: SFTP `make test` passed, including SQLBoiler regeneration,
  producer tests, receiver tests, and OpenAPI response/route checks.
