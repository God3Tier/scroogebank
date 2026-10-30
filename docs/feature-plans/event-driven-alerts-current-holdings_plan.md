# CRM-linked current holdings for event-driven alerts

Status: Approved by Azeez (relayed by Hayyun, 2026-10-30). CH-01 to CH-05 are implemented and verified locally on
`task/event-driven-alerts/current-holdings`; nothing has been applied to AWS. Draft PR [#165](https://github.com/cs301-itsa/project-2026-27t1-g3-t6/pull/165) awaits review and CI. See sections 12 and 12.1.
Owner: Azeez. Domain/API/migration reviewer: Hayyun.
CRM ownership/security reviewer: Neo Jia Wen (coordination required before shared-service edits).
Last verified: 2026-10-30, Asia/Singapore (implementation re-checked against sections 5 to 11 the same day).
Planning branch: `task/event-driven-alerts/current-holdings-plan`, created from
`origin/feat/event-driven-alerts` at `dedb306`.
Parent: [event-driven alerts plan](event-driven-alerts_plan.md).
Decision draft: [ADR-0066](../adr/ADR-0066-crm-linked-current-alert-holdings.md).
Evidence: [remote-branch audit](../testing/alert-holdings-branch-audit-2026-10-29.md).

## 1. Verified problem and intended outcome

The remote audit found an existing, deliberate synthetic-MVP boundary, not a
broken CRM account insert. The CRM persists clients, accounts and agent assignment,
but no maintained securities-position source was found. Every alert holdings
provider found still reads the bundled JSON fixture. Existing `holding_snapshot`
rows are scoring evidence, not a current portfolio maintenance API.

Desired outcome: an agent records dummy investment holdings against an existing
CRM account. Subsequent worker runs use those database records and the client's
current CRM agent without editing JSON or rebuilding an image. Creating a client
with no holdings does not fabricate holdings or make an empty account searchable.
The summary remains decision support for the agent; no client contact or trade
is performed.

### Agent-event experience clarified by the feature owner, 2026-10-30

The primary workflow is an agent receiving an event briefing: "This happened;
these clients may be affected; would you like to inform them, monitor, or dismiss?"
It is not a requirement for the agent to inspect each client and request a
personalised investment review.

- Show one card per authenticated agent and public event, with one shared news
  summary, evidence/citations, why it matters and that agent's affected-client
  count/list. Multiple eligible client/instrument/account evidence rows must not
  become duplicate primary news cards.
- Holdings support relevance, priority and the optional affected-client drill-down.
  They do not require a separate model-generated recommendation per client.
  Keep client exposure/scoring evidence internally and display it on drill-down.
- The agent chooses Inform clients, Monitor, or Dismiss from the event workflow.
  Inform records intent and an explicit selected client set; it does not send a
  message, execute a trade, or silently choose every client. Preserve the existing
  meaning that an inform decision is not proof of contact.
- Group by the identified public event, not ticker alone. Keep separate agents'
  cards, membership and decisions isolated. A client appearing through two
  accounts counts once in the card's client count.
- Specify the agent-event read/decision contract and its relation to existing
  per-client decision rows before implementation. Persist an auditable group
  operation and explicit member outcomes; do not disguise it as an untracked
  browser loop over client endpoints. Enforce current ownership, idempotency and
  concurrency on the selected membership. New members must not inherit an old
  inform intent automatically.
- Existing shared briefing generation is reusable. Current per-client alert
  storage/API/UI are the technical baseline, not evidence that this grouped
  experience has already been implemented. Grouping/API/UI work belongs to the
  new plan after the current retrieval changes are integrated.

There is a related correctness defect: the existing provider filters to affected
instruments before exposure is calculated. A single affected holding can therefore
become 100% of the returned subset rather than its actual account share. The
replacement must return complete portfolios for accounts containing a match.

## 2. Scope and constraints

- Reuse event-alert-service, alerts-db, the existing instrument master, scheduled
  worker, deterministic scoring and audit outbox.
- Use current CRM client/account UUIDs and authoritative assigned-agent subjects.
  Never map `syn-client-001` to a CRM UUID by row order or a guessed relationship.
- Add one complete account-holdings read/replace API and a small holdings editor
  within the existing client/account experience. API tests precede UI work.
- Add the agent-event card/read/decision workflow described above; holdings entry
  is supporting maintenance, not the primary news-review journey.
- Keep all project records dummy/synthetic; editable database data is still dummy
  data. No live price feed, market-data subscription, new microservice, broker,
  vector store, or trading ledger.
- Keep privacy, quotas, four-issuer rotation and provider enablement gates from
  the reviewed retrieval work. This integration does not establish news recall
  or authorize AWS/provider enablement.
- Do not infer holdings from initial deposits, account type or deposit/withdrawal
  transactions. Those fields do not identify securities.
- No AWS, Tavily, model, or deployed-database calls for this planning task.

## 3. Prerequisites and branch path

2026-10-30: the current-changes integration includes the existing retrieval work
and clarified product plan together. This resolves their separate-code baseline
for the next feature task; it does not implement database holdings, grouped cards,
or current-dev CRM synchronization. See the
[integration record](../testing/event-alert-integration-2026-10-30.md).

1. Integrate/review the existing search-universe and retrieval work through their
   task-to-feature PRs. The local retrieval branch ends at `2347a8b`; its fixes
   are not present on the feature tip used to create this planning branch.
2. Coordinate the feature's current-dev synchronization through the normal PR
   path. Inspect ADR-0059/0060/0063 and their actual implementation on `dev`,
   including renamed `assigned_agent_id`, account-owner propagation, admin
   read-only client data, and OpenAPI contract testing. Do not cherry-pick a
   schema rename or copy an old feature-branch account DTO over newer code.
3. Accept ADR-0066 and freeze the new contracts with Hayyun and the CRM owners.
   The absent root `SKILLS.md` registry is a repository documentation gap; no
   unreviewed skill or dependency needs installing for this work.
4. Create implementation branches from the updated parent features:
   `task/event-driven-alerts/current-holdings` and coordinated CRM contract
   task(s) from their owning feature. The final path is task -> feat -> dev ->
   staging -> main, with PR/CI/review gates. This plan performs no merge or push.

## 4. Data ownership and schema

| Data | Source of truth |
|---|---|
| Client existence, deletion and current agent | client-profile-management-service |
| Account existence, type, status, currency and client link | account-service |
| Agent user identity and role | existing user-service/Cognito workflow |
| Manually recorded current securities holdings and supplied valuations | alerts-db, for this feature's dummy-data scope |
| Supported issuer aliases and official sources | governed alert instrument master |
| Historical inputs, scores and decisions | immutable alert snapshots and audit records |

Proposed new tables:

- `current_portfolio`: CRM `account_id` primary key, verified `client_id`,
  optimistic `revision`, source label, update time and actor. Account/client
  IDs are references validated by API, never cross-database foreign keys.
  A missing portfolio differs from an explicitly recorded empty portfolio.
- `current_position`: composite key `(account_id, instrument_id)`, quantity,
  unit price, valuation currency, FX-to-SGD and actual `valued_at`. Use existing
  decimal conventions: money `numeric(19,4)`, quantity/FX `numeric(24,8)`.
  Derive SGD value server-side; reject overflow, invalid scale, future dates,
  negative/zero prices or FX, duplicate instruments and unsupported instruments.
  A replacement contains positive positions; removing a row records a disposal
  in this representation, without executing a trade.
- `portfolio_snapshot`: immutable snapshot ID, account/client references,
  portfolio revision, observed agent and total used for one scoring evaluation.
  Link new `holding_snapshot` rows to this group so valuation dates do not
  accidentally determine membership or combine accounts.

The current `synthetic_portfolio.client_ref` primary key permits only one account
per client and is referenced by historical snapshots. Do not reuse it as the
new current ledger. Plan an additive forward migration that lets new snapshots
use CRM-linked account references, removes/replaces the old fixture-only FK as
needed, and retains all legacy rows. Do not invent a historical snapshot group
where the old evidence cannot determine it. Legacy detail remains explicitly
legacy; new detail uses the recorded snapshot group and total.

Permit multiple accounts per client and the same instrument in different
accounts. Replacing current positions must never overwrite a historical
valuation, alert decision or original creation-agent evidence.

## 5. CRM directory integration and authorization

Existing `POST /internal/accounts/lookup` returns IDs, client IDs and, on current
dev, currency. It does not supply current client assignment and is protected for
the transaction importer. Do not silently grant the worker that credential.

**Implementation decision (2026-10-30): one route, not two.** On `dev`, a client reassignment moves the
client's accounts to the new agent in the same transaction (ADR-0060: "a client and its accounts must never
end up with different agents"), so `accounts.owner_agent_id` is the client's current agent. The alert service
therefore needs only the account route, which returns the owner, and no client-service route or
service-to-service login there. The cost: it cannot cross-check the account's agent against the client's.
The text below is the original two-route proposal; the account route is built as described in
`docs/api/accounts-api.yaml`, and the client route is not built.

Propose `POST /internal/alerts/accounts/lookup` on account-service and
`POST /internal/alerts/clients/lookup` on client-service, each accepting
`{"ids": [...]}` with at most 200 distinct UUIDs. Return only IDs,
account type/status/currency, assigned-agent subject, and source update/version
metadata. Unknown/deleted IDs are omitted with explicit consumer handling.
Document routes in the owning services' OpenAPI contracts. Keep transaction
lookup responses and scopes compatible.

- Background worker/API directory reads: a dedicated allowlisted
  client-credentials identity with only the new read scopes, verified issuer,
  client ID, signature, expiry and scope; refuse user tokens on these routes.
  Cache tokens only to their expiry safety margin; never log them.
- Holdings writes: the authenticated current agent only. Forward their token to
  existing scoped account/client checks and compare the verified relationships.
  The request cannot choose a client or agent. Admins read but do not write.
- Account and client owner disagreement, deleted/missing records, wrong type or
  non-active account: block new tracking/mutation; preserve historical evidence.
  A transient upstream error is unavailable, not a confirmed deletion.
- Before search and again before fan-out, resolve current assignment for the
  accounts actually represented in current holdings. Do not fetch all CRM PII.
- All alert list/detail, decision and feedback operations must use current CRM
  assignment, not just the creation-time `alert.assigned_agent_subject`.
  Refresh the minimal assignment projection for relevant local CRM references
  before SQL ownership filtering and cursor pagination; fail the request if the
  refresh is incomplete. Start with a bounded demo population of 1,000 distinct
  referenced clients; exceeding it returns an explicit unavailable/capacity
  outcome, never a silently truncated authorization set.
- No long-lived positive ownership cache. Recheck current authority on mutations.
  Completed reassignment A -> B must deny A and admit B. Preserve original actors
  and snapshot assignment as history; B's new decisions record B.
  Cross-service checks are observations, not a distributed transaction: document
  the check-to-commit race and require failure/retry on a detected assignment
  version change. Do not claim atomic revocation across services.
- Directory outage: fail closed (503 for protected API operations; defer/fail the
  worker without marking pending sources permanently `NO_HOLDERS`). Never
  silently fall back to fixture clients or an old assignment.

## 6. Holdings API and entry workflow

Proposed alert-service routes (OpenAPI contract first):

| Method | Path | Behaviour |
|---|---|---|
| GET | `/api/alert-portfolios/{accountId}` | Current complete portfolio and revision; owning agent or read-only admin |
| PUT | `/api/alert-portfolios/{accountId}` | Atomically replace the complete recorded portfolio; current agent only |

A replacement accepts `expectedRevision`, source label and at most 100 positions.
Require an idempotency key: same actor/key/body replays the response; different
body with a used key or stale revision returns 409. An explicit empty list clears
current positions but preserves revision/audit/history. Unknown instruments
reject the whole replacement (422), rather than silently shrinking its total.
No arbitrary SQL or automatic seed copying is the normal entry path.

For initial scope, record positions only on active investment accounts. The
editor shows the account/client context from CRM, supported instruments, supplied
valuation time, derived value and the fact that this is project dummy data.
Saving is bookkeeping, not a buy/sell order. Admins see no write controls.
The demo can use a local seed/import client calling this same API with synthetic
records and legitimate test identities; never re-use production tokens or print
local credentials. A broader instrument-onboarding API remains a separate
governed task; adding holdings does not auto-discover a new ticker's metadata.

## 7. Provider replacement, scoring and historical meaning

Implement database-backed providers behind an explicit `fixture|database` mode.
Fixture mode is for isolated demos/tests. Database mode must not instantiate or
fall back to the fixture provider; API and worker must use the same mode.

One bounded database snapshot/revision set feeds selection and evaluation. Supply
full position sets for each affected account, including unaffected securities.
Freeze an internal holdings-read v2 contract (or a clearly separate v2 method):
the affected IDs select accounts, not the positions in the denominator. Avoid
silently changing the meaning of the existing v1 read result.

- Preserve inclusive 20%/minimum-two-client commonality OR 50% per-account
  concentration after the prerequisite universe work is integrated.
  Commonality denominator is distinct clients represented by positive holdings,
  not all CRM user accounts. It remains independent of valuation freshness.
- Concentration and exposure use the full account valuation and current freshness:
  fresh <=24h, stale <=72h with warning, expired >72h unavailable.
  Never refresh `valued_at` merely because the worker runs.
- Missing/invalid/expired or incompatible portfolio values make concentration
  and alert exposure unavailable; never remove a bad unaffected position and
  calculate a misleading percentage from the remainder.
- Store the exact portfolio revision, numerator, denominator and snapshot group
  used for each new alert. Detail reads reproduce that result after later edits.
- Continue the existing one-alert-per-client/event/instrument deduplication rule:
  this is internal evidence cardinality; the primary UI still groups these rows
  into one agent-event card as clarified above.
  evaluate each affected instrument against the full portfolio separately, rather
  than attach a combined exposure to whichever affected row is encountered first.
  if a client holds the same instrument in multiple accounts, evaluate each
  account separately and select the highest qualifying account score, with a
  deterministic account-ID tie break. Do not sum account percentages or silently
  change alert cardinality. Detail identifies the selected account; this choice
  requires Hayyun's ADR review.
- New/changed portfolios affect the next scheduled run. Do not promise automatic
  reprocessing of already-finished historical news or instantaneous searching of
  every eligible instrument. Rotation and successful coverage remain in force.

## 8. Audit, privacy, operations and cost

Persist replacement, clear and routing-change audit events transactionally with
their local mutations. Include opaque resource IDs, actor, revision before/after,
outcome and correlation/idempotency references; exclude raw quantities, valuations,
credentials, client names and payload bodies. Audit unavailability/capacity must
roll back the holdings write.

Instrument metadata is the only portfolio-derived input permitted in Tavily/model
requests. Inspect actual captured requests in tests for client/account/agent IDs,
quantities and valuations. Metrics use aggregate counts only: maintained portfolios,
missing holdings, expired valuations, invalid CRM links, directory failures and
pending-source deferrals.

No new database instance or always-on service is proposed. Private directory
traffic, machine-token requests, secret storage, and new metrics can add cost;
measure them and update the existing budget before cloud enablement. Reuse the
current service topology, add only necessary network rules/secrets/scopes, and run
Terraform fmt/validate/native tests offline. No AWS plan/apply or price claim is
part of this plan.

## 9. Implementation sequence and proposed ownership

Owners follow existing boundaries; cross-service owner agreement is required
before implementation. No teammate has been contacted by this planning task.

| Task | Primary owner | Expected files/components | Branch / dependency | Status |
|---|---|---|---|---|
| CH-00: approve model/contracts and reconcile current dev | Azeez; Hayyun domain review; Neo Jia Wen CRM review | This plan, ADR-0066, parent plan, account/client contracts | `task/event-driven-alerts/current-holdings-plan`; prerequisite retrieval and CRM PRs | Approved by Azeez (relayed 2026-10-30) |
| CH-01: private CRM account/client context | Neo Jia Wen, proposed pending owner coordination | account/client controller/service/repository/security tests; accounts/clients OpenAPI | Owning CRM feature's `task/*/alert-directory`; CH-00 | Implemented for account-service only (see section 12); awaiting Neo Jia Wen's review |
| CH-02: current holdings schema, API and audit | Hayyun | alert domain/repository/API/security, forward Flyway migration, schema and OpenAPI | `task/event-driven-alerts/current-holdings`; CH-00/01 | Implemented and verified locally (section 12) |
| CH-03: DB providers, full-account exposure, snapshot detail and current-owner routing | Azeez with required Hayyun review | holdings/ingestion/processing/repository/API authorization and integration tests | Same alert task; CH-02 and retrieval fixes | Implemented and verified locally (section 12) |
| CH-04: existing account-page holdings editor | Hayyun | existing frontend client/account and alert views, API adapter and tests | Alert task after API contract; coordinate frontend owner | Implemented and verified locally (section 12) |
| CH-04b: one agent-event briefing with affected-client drill-down and event actions | Hayyun; Azeez reviews shared briefing/retrieval boundary | agent-event read/decision API, mapping to client evidence, grouped alert frontend, contracts/audit and tests | After grouped contract agreement and current retrieval integration; implemented on the alert task branch | Implemented and verified locally (section 12) |
| CH-05: Terraform wiring, validation and developer handoff | Receiving infrastructure developer (record named owner before editing); Azeez application coordination; Neo Jia Wen infrastructure review | CRM/alert-worker Terraform, service configuration, runbooks, cost/evaluation evidence; detailed handoff below | `task/event-driven-alerts/current-holdings-infra` from updated feature; CH-01..04/04b configuration contracts | Implemented and validated locally (see section 12); no named infrastructure developer yet; not applied to AWS |

All tasks last synchronized on 2026-10-29. Execute sequentially unless owners
explicitly coordinate separate work; do not delegate or edit teammates' areas
merely because this table proposes work.

### 9.1 CH-05: Terraform handoff for the receiving developer (2026-10-30)

This is an addition to the existing plan, not a replacement. A separate developer
will take over implementation. Record that developer's name against the tasks
they accept before editing, and coordinate with the listed service/domain owners.
Azeez remains the feature owner and application-interface contact; no teammate
approval or implementation is implied by this handoff.

#### What is changing and why Terraform must change

The application will read maintained database holdings linked to current CRM
accounts and agent assignments. Its primary agent experience remains one event
briefing with an affected-client list and Inform / Monitor / Dismiss choices.
Terraform must supply the private connectivity, authentication, configuration
and routing for that workflow. Local code working against Docker does not prove
that ECS API/worker tasks have these dependencies when deployed.

The present alert API and worker do not have directory credentials/endpoints,
the alert-service load-balancer rule covers only `/api/alerts`, and the run-once
worker does not have an ECS-service Service Connect proxy. Merely configuring
Service Connect alias hostnames on that task does not establish connectivity.

#### Start here: branches, ownership and application contracts

1. Read `AGENTS.md`, `project_plan.md`, this plan and the applicable ADRs.
   Inspect status, current branch and recent commits; preserve unrelated edits.
2. Run `git fetch --all --prune`. Inspect relevant remote feature/task branches
   for existing CRM ownership, directory, identity and infrastructure work.
   Fetching all branches does not mean merging them all.
3. Create `task/event-driven-alerts/current-holdings-infra` from the latest
   `origin/feat/event-driven-alerts`. If that name already exists, inspect its
   owner/history before reusing it. Record the named task owner in this plan.
4. Coordinate any prerequisite current-dev synchronization through the owning
   feature's normal workflow. Do not copy old account DTOs, authentication
   middleware or schemas over newer CRM reassignment/authorization changes.
5. Freeze the application configuration names, scopes, directory routes and
   provider-mode behaviour with Azeez and the service owners before wiring them.
   Test both producer and consumer configuration; do not guess environment names.
   Implement and verify the agreed Terraform changes on the task branch.

#### Required Terraform review and updates

- API and worker must use the same explicit holdings mode and directory
  configuration. Database mode must not silently fall back to fixture data;
  missing required configuration must fail explicitly. Ingestion/model switches
  remain off by default.
- Add a dedicated allowlisted Cognito client-credentials identity with only the
  account/client directory read scopes. Configure the directory services to
  recognise the exact client and route-specific scopes. Preserve the transaction
  importer's existing credentials, scope and lookup contract.
- Inject the credential through Secrets Manager/ECS secrets. Grant access only
  to the execution roles requiring it; do not add broad Cognito or secret-read
  privileges to application task roles. No secret values in ordinary environment
  maps, outputs, examples, commits or logs. Document and protect any
  Cognito-generated credential material held in Terraform state.
- Establish private HTTPS access to the directory routes for both API and
  run-once worker. Use the existing private ALB path for this batch-task
  integration, with the necessary security-group rules, listener routing, DNS and
  certificate trust. Reuse the Java trust-store/TLS-init mechanism where needed;
  the worker is a TLS client, not a new HTTPS server. Database mode must not
  downgrade to HTTP if the deployment lacks a usable HTTPS endpoint.
- Add authenticated `/api/alert-portfolios` and descendant paths to the existing
  alert-service target. Keep grouped event routes under `/api/alerts`.
  The private directory routes remain outside the public API Gateway surface.
  Document the deployment prerequisites for any required internal HTTPS route.
- Extend the CRM stack's `alerts_worker_inputs` additively with required endpoint,
  credential-reference and trust/configuration data. Preserve existing keys and
  update the alert-worker consumer and mocked remote-state fixtures together.
- Reuse the existing alert database, API, batch worker, scheduler and alarms.
  No new database instance, always-on service or message broker is required.
  Forward schema migrations remain application-owned and precede database-mode
  activation. Check applicable API/worker startup and health behaviour.
- Expected work is in `stacks/crm`, `stacks/alert-worker`, and only where needed
  `modules/ecs-service` under `infrastructure/terraform`. Update environment
  examples, operational configuration, deployment order, rollback and cost notes.
  Do not expand shared modules or unrelated stacks without a demonstrated need.

#### Tests and definition of completion

Run formatting checks, backend-disabled initialization, validation and mocked
Terraform tests for every changed stack/module. For each affected directory:

```sh
terraform fmt -check -recursive
terraform init -backend=false -input=false
terraform validate
terraform test
```

Terraform tests must mock AWS providers and override CRM remote-state reads.
Do not run a live AWS plan/apply or access deployed state for these local checks.
Keep existing tests passing and add assertions proving:

- API and worker receive matching holdings mode and directory configuration.
- Dedicated credentials have only approved directory read scopes; existing
  transaction-import permissions/configuration remain compatible.
- Secret references and IAM resource permissions are narrowly scoped and no
  secret value appears in plain task environment variables or normal outputs.
- Private directory routes are not exposed by API Gateway; authorised private
  callers have the required network/listener path.
- The run-once worker receives functional HTTPS endpoint/trust configuration,
  without depending on an absent Service Connect proxy.
- Public holdings routes reach the alert API, and existing alert routes work.
- Added output keys preserve the existing CRM/worker interface.
- Staging/production, running/stopped and fixture/database configurations work;
  absent required database-mode settings are explicitly rejected.
- Scheduler stop/start behaviour, failed-run alarms, quota settings and default-off
  ingestion/model controls retain their existing guarantees.

The application developer must also exercise these configuration contracts with
local services and stubbed token/JWKS/directory endpoints: permitted identity,
wrong scope/client, expired token, upstream timeout and no fixture fallback.
Changing environment names requires matching application and configuration tests.

CH-05 is complete only when the changes are implemented, all affected existing
and new tests pass, configuration matches the application contract, and the
runbooks describe deployment order and rollback. Include security/cost notes and
exact commands/results in a reviewed PR to `feat/event-driven-alerts`; do not
infer permission for a future direct integration from the earlier documentation
push. Report remaining deployment checks explicitly.

Local mocked verification is not proof of AWS deployment. AWS apply, secrets
population, live connectivity and enabling news/model calls require separate
authorization. This handoff itself makes no infrastructure or application change.

## 10. Acceptance and test matrix

1. Create a new dummy CRM client and active investment account through their APIs;
   record a portfolio through the new API. The next worker read includes it
   without changing JSON, restarting a container or rebuilding an image.
2. A client without holdings is visible in CRM but contributes no invented position.
   Adding/removing positions changes selection according to exact thresholds.
3. 30 clients across multiple agents, markets, multiple accounts per client and
   multiple instruments per account. Distinct clients, not accounts, determine
   commonality; concentration stays within one account.
4. Full-portfolio regression: affected value 10, unaffected value 90 -> 10%, not
   100%. A second account's value never changes that denominator. An invalid
   unaffected valuation blocks evaluation instead of disappearing.
5. Same instrument in two accounts of one client -> deterministic selected-account
   score and one existing-cardinality alert; no double count or merged percentage.
6. Reassign A -> B through the real CRM workflow. B can read the holdings/new and
   existing alerts and decide on open alerts within the existing state machine;
   A cannot list, view, mutate or use an old cursor
   to retrieve them. Original decisions/snapshots remain unchanged.
7. Cross-agent/account spoofing, admin writes, wrong service client/scope,
   expired/forged tokens and user/service token mixing are refused.
8. Unknown/deleted clients/accounts, closed/pending/non-investment accounts,
   inconsistent assignment, upstream timeout/partial response and >1,000-client
   refresh cap fail explicitly without leaking data or finalizing a transient
   `NO_HOLDERS` outcome.
9. Concurrent complete replacements, stale revision, idempotent retry, clear-all,
   empty vs missing, transaction rollback and full audit backlog.
10. Fresh/stale/expired, future/incompatible timestamps, decimal/FX correctness,
    unknown instrument and market metadata; no fabricated timestamps/prices.
11. Migration from the existing database with historical alerts and from empty;
    update current positions and prove old displayed exposure stays identical.
12. Inspect captured search/model payloads and logs for private fields.
13. Docker/PostgreSQL integration, real local CRM services with fixture model/search,
    API/worker restart persistence and no fixture fallback; OpenAPI compatibility,
    Java/Go/frontend tests, and changed-stack Terraform validation/native tests.
    No paid calls are necessary for these gates. A separate approved live provider
    evaluation remains required for production retrieval quality.
14. One event affecting five eligible clients of one agent produces one primary
    event card with five distinct affected clients and one shared briefing.
    The same event for another agent shows only that agent's clients; two
    accounts for one client do not duplicate the card or its client count.
15. Event actions are scoped to an explicit current authorised client set and
    audited/idempotent. Monitor/dismiss/inform semantics and member-level
    outcomes are contract-tested. No action sends a client communication.
    Retries, partial membership changes and reassignment do not leak another
    agent's clients or apply an old inform intent to newly affected clients.

## 11. Migration, rollback and completion gates

Use the next unused forward-only Flyway version after prerequisite migrations
land; do not hard-code V10 before checking all feature/task migrations. Never
rewrite V1 or V8 on an existing alerts database. Keep database mode off until
schema, directory access and account-linked dummy portfolios are ready.

Roll out locally first: services with stubbed signed-token/JWKS and token-endpoint
tests, PostgreSQL via Docker and fake public providers. Seed through the API using
current CRM IDs and explicitly supplied fresh dummy valuations. Then test mode
consistency between API/worker and deployment config with outbound switches off.

Rollback disables ingestion/model and holdings mutations, retains all new data and
audit evidence, and reverts application/config only to a compatible version.
Never use fixture fallback or restore former-agent permissions as a rollback.
Reconcile mismatched CRM assignment through the owning CRM workflow.

Do not mark complete until CH-00 approvals, code/contract review, all local tests,
migration/recovery evidence, updated runbooks/schema/budget/AI log and required
CI pass. Cloud deployment and production retrieval enablement remain separate
authorized steps.

## 12. Implementation record (2026-10-30)

Implemented on `task/event-driven-alerts/current-holdings` (cut from `feat/event-driven-alerts`, then `dev` merged
in) at Hayyun's request after Azeez's approval. The merge to `feat/event-driven-alerts` and `dev` is not done and
needs the PR path in section 3.

| Task | State | Evidence |
|---|---|---|
| CH-01 CRM directory | **Done for account-service, one route** (decision in section 5). Needs Neo Jia Wen's review before merge | `POST /internal/alerts/accounts/lookup`, own scope `crm-accounts/alert-directory` and client allow-list `COGNITO_ALERT_DIRECTORY_CLIENT_IDS`; `docs/api/accounts-api.yaml`; Go tests; real-service run `e2e-real-account.mjs` |
| CH-02 schema, API, audit | Done | `V10__current_holdings.sql`; `PortfolioService`, `PortfolioController`; `CurrentHoldingsIntegrationTest`, `PortfolioControllerTest`, `LegacyMigrationTest` |
| CH-03 database providers, full-account exposure, snapshot detail, current-owner routing | Done | `DatabaseHoldingsProvider`, `CurrentHoldingsReader`, `DatabaseSearchUniverseProvider`, `SearchUniverseSelector`, `CurrentOwnership`, `AlertGenerationService`; `DatabaseHoldingsModeApplicationTest`, `SearchUniverseSelectorTest` |
| CH-04 holdings editor | Done (investment accounts, on the client's accounts page; not shown to admins) | `HoldingsEditor.jsx` and tests |
| CH-04b event briefing, drill-down, actions | Done | `AgentEventController`, `AgentEventActionService`, `EventBriefings.jsx` and tests; routes in `docs/api/openapi.yaml` |
| CH-05 Terraform | **Done and validated locally; not applied.** No infrastructure developer is named yet | `stacks/crm` (Cognito scope and app client, secret, `alert_holdings_mode`, ALB rules, security-group rule, outputs) and `stacks/alert-worker`; `terraform fmt`, `validate` and mocked `terraform test` (11 and 8 passing) |

### 12.1 Verification, deviations and what is left (2026-10-30)

Run and passing on this branch:
- `mvn -pl services/event-alert-service test`: PostgreSQL in Docker (Testcontainers). Includes the contract tests
  that check every route and response against `docs/api/openapi.yaml` (ADR-0063).
- Frontend: `npm ci && npx vitest run && npx eslint src && npx vite build`.
- account-service: its Docker test run (`make test` steps: SQLBoiler, mockery, `go test ./...`), including
  `TestEveryRouteIsInAContract` and `TestInternalRoutesUseTheirOwnServiceMiddleware`. `make` is not installed on
  Hayyun's machine; the same steps were run with Docker Compose directly.
- Terraform, in `stacks/crm` and `stacks/alert-worker`: `terraform fmt -check -recursive`, `init -backend=false`,
  `validate`, `test` (no AWS access).
- Real-process checks (need Docker and Node): `node scripts/local-crm-stub/e2e.mjs` (stub CRM, 19 checks) and
  `node scripts/local-crm-stub/e2e-real-account.mjs` (the **real account-service image and database**, with only
  Cognito stubbed, 26 checks: route security, per-scope refusal, ownership, real reassignment through
  `/ownership/accounts`, closed and deleted accounts, outage, restart persistence, one-shot worker).
  See `scripts/local-crm-stub/README.md`.

Deviations from this plan, on purpose:
1. One CRM route instead of two (section 5).
2. Holdings writes check ownership twice and both must agree: the directory's answer (service credential) and the
   CRM's own check of the agent's forwarded token (`GET /api/accounts/{id}` on account-service, through the same
   internal ALB rule that already forwards `/api/accounts/*`). Added 2026-10-30; no Terraform change.
3. Work was done on a task branch off `feat/event-driven-alerts` with `dev` merged in, not by PR to the feature.
4. In the CRM stack, `alert_holdings_mode` is the one switch for the API and the worker. Database mode requires
   `domain_name` (plan fails without it) so the directory is never reached over HTTP. The worker uses the JVM's
   default trust store, which trusts the ALB's public certificate.

**Not done, and why. Do these next:**
1. **Review and merge**: Draft PR [#165](https://github.com/cs301-itsa/project-2026-27t1-g3-t6/pull/165)
   targets `feat/event-driven-alerts`. Azeez, Hayyun and Neo Jia Wen review it; Neo Jia Wen reviews the
   account-service route and the Cognito/ALB Terraform. The branch includes a previous `dev` merge, so the
   feature branch needs synchronizing through review before the PR diff is holdings-only and ready for focused review.
2. **Run against real AWS and a real Cognito pool.** Nothing here was applied. Applying the CRM stack with
   `alert_holdings_mode = "database"`, populating nothing by hand (the secret is created by Terraform), and
   checking an agent can record and read holdings needs separate authorization (section 11).
3. **Real client-service reassignment: done (2026-10-30)** with `scripts/local-crm-stub/e2e-reassignment.mjs`:
   the real client-service image, its own PostgreSQL, the real account-service and the alert service; only
   Cognito and user-service are stand-ins (15 checks). An admin reassigns through
   `POST /api/clients/reassignments`; the client, both accounts and the alert service's access all move, and a
   refused reassignment moves nothing. LocalStack (KMS/S3) was not needed, because reassignment touches no
   encrypted data.
4. **Alert generation in a running process: partly done.** `scripts/local-crm-stub/e2e-live-generation.mjs`
   records a DBS holding through the API, then runs the worker against the **real Tavily API** (key from the
   `TAVILY_API_KEY` environment variable, never stored or logged): the portfolio drove the search universe, a
   real search ran and succeeded, and 10 real results were all rejected by the service's relevance filter
   (accepted 0). **No alert was created**, because the pipeline skips source processing when the model is off
   (`skipped=MODEL_DISABLED`), and enabling the model needs Bedrock on AWS. Generation, grouping, scoring and
   actions remain covered by the PostgreSQL integration tests only. Not run: model-on alert creation.
5. **Section 8, now done (2026-10-30):** the `alert.routing-changed` audit event, written in the same transaction
   as the ownership refresh that sees the change (fails closed when the outbox is full; test in
   `CurrentHoldingsIntegrationTest`); the aggregate gauges and directory-failure counter (maintained portfolios,
   missing and expired positions, invalid CRM links, directory failures; pending deferrals already existed;
   test `aggregateGaugesDescribeTheBookWithoutIdentifiers`); and the captured-request privacy check
   (`OutboundPrivacyTest` drives the real Tavily client and classifier/briefing generator and searches every
   request for client, account, agent, quantity and valuation values).
6. **Small gaps: closed locally (2026-10-30).** The agent-event list now returns a keyset `nextCursor`, and the
   frontend loads further cards. Event drill-down names the account whose holding produced each alert's score.

### 12.2 Independent local rerun (2026-10-30)

On `task/event-driven-alerts/current-holdings`, Codex reran the following with
synthetic data and no AWS access:

- Alert service: `mvn -B -pl services/event-alert-service -am test` — 242 passed, 0 failed.
- Frontend: `npm ci --no-audit --no-fund`, `npx vitest run` — 261 passed;
  `npx eslint src` and `npx vite build` passed.
- account-service and client-profile-management-service: generated ignored ORM
  code and mocks, then ran each service's Docker Compose `go test ./... -cover`;
  both passed. The client suite used LocalStack for its encryption/document tests.
- Terraform: `fmt -check -recursive`, `validate` and mocked `test` passed in both
  `stacks/crm` (12 tests) and `stacks/alert-worker` (8 tests). No plan or apply
  against AWS was run.
- Real-process checks: `e2e.mjs` (19), `e2e-real-account.mjs` (26) and
  `e2e-reassignment.mjs` (15) all passed. Windows reserved the scripts' default
  PostgreSQL port, so `ALERT_E2E_DB_PORT=5438` was used. These checks cover
  current portfolio output, revision and audit evidence, authorization, restart
  persistence, and reassignment through the real client and account services.
- `e2e-live-generation.mjs`: a bounded Tavily search succeeded using the ignored
  local key. One result was rejected by the relevance gate; accepted sources,
  events, alerts and snapshots were all zero. The worker reported
  `skipped=MODEL_DISABLED`. This proves the maintained holding drives a live
  search, but does **not** prove a live generated briefing. The key was not
  printed or committed.

The PostgreSQL integration tests assert deterministic alert and briefing output,
including complete-account 10/100 exposure, grouping, current-owner visibility,
selected-client actions and immutable prior evidence. A running live-provider
briefing remains unverified without an enabled model. Required owner reviews,
PR #165 CI, and AWS validation remain open; the latter is outside this local rerun.

### 12.3 Local follow-up on the event briefing (2026-10-30)

The event list now uses the same opaque timestamp/UUID cursor format as the
per-alert list. The server rechecks current CRM ownership on every page and
returns `nextCursor` only when more event cards exist. The UI appends pages and
shows the scoring account on affected-client drill-down; the API contract and
synthetic regression tests cover these fields. No Terraform resource changed.

Verification: the full alert-service suite passed (243 tests), followed by all
18 `CurrentHoldingsIntegrationTest` cases after adding an open-only cursor
assertion. The frontend passed 262 tests with two workers and a 15-second test
timeout, plus ESLint and the production build. The default high-concurrency
Vitest run had two unrelated five-second paging-test timeouts (260 passed);
both passed in the bounded rerun. Terraform, Go and real-process evidence in
section 12.2 remains applicable because those paths were not changed.

The retrospective Tavily benchmark in
[`tavily-global-portfolio-benchmark-2026-10-29.md`](../testing/tavily-global-portfolio-benchmark-2026-10-29.md)
met the numerical ADR-0051 thresholds on eight previously labelled events,
but the reference set came from the earlier investigation. Independent human
labels, commercial-source terms approval, and provider-dashboard credit
confirmation remain production gates. A further unlabelled live search would
not close those gates. No AWS call or infrastructure apply was made here.
