# Client Health Monitor — Feature Plan

## Plan metadata

- Status: In progress; SF00-SF06, offline rule evaluation and production SF02/SF04 persistence are merged; RH-SF08 leased JDBC/SQS delivery is implemented on its task branch
- Project feature: Feature 5
- Feature owners: Garvit and Swayam
- Feature branch: `feat/client-health-monitor`
- Current task branch: `task/client-health-monitor/audit-delivery`
- Source specification: CS301 Project v18.0
- Last repository sync: 2026-10-26 from `origin/feat/client-health-monitor` at `3f33f91`
- AI-assisted planning: Yes; Cursor

ADR-0034 accepts the feature-wide Java/ECS, deterministic-rules, health-db,
Bedrock-fallback, authorization, outbox and observability direction. ADR-0044
accepts API-based transaction intake and replay for RH-SF01. Detailed assignment,
provider, persistence and deployment contracts remain coordinated follow-up work.

## Problem statement

CRM agents can own many client relationships and cannot continuously inspect every client's account activity. A gradual change in transaction behaviour may therefore be noticed only after the relationship has weakened or the client contacts the bank.

The Client Health Monitor identifies explainable changes in synthetic transaction activity that may justify human review. It creates one client-level follow-up task for the assigned agent and records the alert and subsequent agent actions.

The feature supports relationship-engagement monitoring, service prioritisation, and client retention. It must not claim that a client is leaving, financially distressed, fraudulent, laundering money, or unsuitable for a product.

## Business value

- Help agents prioritise proactive human follow-up.
- Reduce continuous manual transaction inspection.
- Show deterministic evidence behind every generated task.
- Preserve human judgment before any client contact.
- Measure whether alerts are useful or merely noisy.
- Add a predictive, client-focused CRM capability beyond record storage.

## Distinction from Dynamic Event-Driven Client Alerts

Azeez and Hayyun's Dynamic Event-Driven Client Alerts feature analyses public financial news and maps market events to synthetic investment holdings.

This feature:

- uses internal synthetic transaction/account activity;
- does not search or classify public news;
- does not use securities, issuers, holdings, prices, valuations, or portfolio exposure;
- does not generate investment or trading recommendations; and
- creates relationship-health follow-up tasks rather than market-event briefings.

Shared UI conventions, Cognito configuration, Bedrock controls, correlation IDs, and audit envelopes may be coordinated. Business logic and databases remain separate.

## Users and authorization

### Assigned Agent

An Agent may:

- list and view tasks for their own assigned clients;
- inspect signal evidence and the AI/template explanation;
- accept an `OPEN` task, moving it to `IN_REVIEW`;
- complete an `IN_REVIEW` task;
- dismiss an `OPEN` or `IN_REVIEW` task with a structured reason; and
- view task status history.

An Agent must not view or modify another agent's client tasks.

### Admin

For the MVP, an Admin may read tasks and histories across agents for oversight. Admin is read-only and cannot accept, complete, dismiss, reopen, or reassign a task.

### Authentication

- User-facing APIs validate shared Cognito OAuth2 access tokens.
- Role and agent identity come from verified token claims.
- Caller-supplied agent identifiers are never trusted.
- Machine-to-machine authentication requires an accepted architecture decision.

## MVP scope

- Receive or retrieve matched synthetic transaction activity through an approved versioned contract.
- Support replay of six months of synthetic history.
- Maintain only the minimum local activity projection required by the rules.
- Load a configurable, versioned ruleset.
- Evaluate three deterministic relationship-health signals.
- Deduplicate repeated inputs and overlapping analysis runs.
- Combine all qualifying signals for one client into one active client-level task.
- Attach new evidence to an existing `OPEN` or `IN_REVIEW` task.
- Use Amazon Bedrock to explain already-verified facts in plain language.
- Validate Bedrock output against a strict schema.
- Use deterministic template explanations when AI is unavailable or invalid.
- Expose authenticated task read/action APIs.
- Enforce Agent ownership and Admin read-only access.
- Store task history and optimistic-lock versions.
- Create audit events through a transactional outbox.
- Record product-usefulness, AI-quality, reliability, and cost metrics.
- Provide OpenAPI/event contracts, schema documentation, tests, and runbooks.

## Explicit non-scope

- Public news and web search.
- Securities, investments, holdings, and portfolio exposure.
- Fraud, AML, sanctions, credit, affordability, or hardship decisions.
- Automatic client contact or product recommendations.
- Blocking transactions or modifying canonical client/account/transaction records.
- Machine-learned churn prediction or training on agent decisions.
- Real client, confidential, or production banking data.
- Admin task mutation.
- Task reopen, reassignment, escalation, expiry, or external notifications.
- Reading another service's database directly.

## Selected MVP signals

### Recurring-credit interruption

Detect that an established recurring external credit is missing beyond its expected cadence.

- Call it an `income-like recurring credit`, not verified salary.
- Require at least three prior observations.
- Under ADR-0052 use median cadence, at most 20% amount variation, and grace
  equal to the larger of seven days or 25% of cadence.
- Show why the stream was considered recurring.

### Transaction-activity decline

Detect that recent completed customer-initiated activity is materially lower than the client's own baseline.

- Compare the client with their own history, not demographic groups.
- Count only explicit `CustomerInitiated` activity under ADR-0051; exclude
  `Automated` and fail closed on `Unknown` rather than inferring from descriptions.
- Require at least six baseline transactions and trigger at a 50% decline under
  the versioned ADR-0052 severity bands.
- Call it an activity decline or inactivity precursor, not official dormancy.
- Suppress clients without sufficient baseline activity.

### Sustained net-outflow trend

Detect that completed withdrawals materially exceed deposits across multiple periods relative to the client's previous pattern.

- Require persistence rather than one large withdrawal.
- Require two consecutive 30-day periods at or below `-SGD 500` and combined
  withdrawals at least 1.25 times deposits under ADR-0052.
- Do not infer destination or client intent.
- Do not describe the result as fraud, financial distress, or proven attrition.
- Define an explicit multi-currency policy before enabling this rule.

## History and baseline design

- Generate six months of synthetic transaction history.
- At the daily 08:00 Asia/Singapore analysis cut-off, compare the previous
  90-day baseline with the latest non-overlapping 30-day comparison window.
- Preserve the full six-month window for recurring-credit grouping.
- Require at least three observations for recurring-credit classification.
- Analyse SGD only for the MVP and fail closed on mixed-currency windows.
- Store rule version and exact input-window boundaries with each signal.
- Keep windows, thresholds, cooldowns, severity, and caps in a versioned ruleset.

The window semantics are accepted for RH-SF02. ADR-0052 accepts versioned V1
thresholds and severity for labelled synthetic evaluation; they are project
defaults, not universal banking standards.

## Combined task and suppression behaviour

- One active client-level task may contain evidence from several accounts/signals.
- New evidence updates the active task instead of creating another.
- Signal fingerprint includes client, account, signal type, rule version, and analysis window.
- Permit at most one active occurrence for the same fingerprint.
- Apply a 30-day configurable cooldown after completion/dismissal under ADR-0053.
- Permit a new task during cooldown only when highest severity strictly increases.
- Require matched, complete data and minimum history.
- Exclude Pending transactions from activity/net-flow calculations.
- Apply a 20-new-task daily cap per Agent/Singapore day; evidence attachment to
  active tasks remains allowed.
- SF04 owns run leases, deterministic IDs/fingerprints and success-only
  watermarks; SF05 atomically owns assignment-aware cooldown/cap admission.

## Task lifecycle

```text
OPEN -> IN_REVIEW -> COMPLETED
  |          |
  +----------+-> DISMISSED
```

- System creates `OPEN`.
- Assigned Agent accepts to produce `IN_REVIEW`.
- Assigned Agent completes only from `IN_REVIEW`.
- Assigned Agent may dismiss from `OPEN` or `IN_REVIEW`.
- Completion and dismissal require structured outcome/reason codes.
- Every transition records actor, time, before/after state, and correlation ID.
- Optimistic locking prevents concurrent silent overwrites.

Post-MVP: reopen, reassignment, expiry, snooze, escalation, supervisor actions, comments, and optional notifications.

## Proposed logical service boundary

The service owns:

- activity projection required for its rules;
- analysis runs and watermarks;
- signal occurrences and evidence;
- relationship-health tasks and status history;
- AI/template explanation records;
- audit outbox records; and
- feature metrics.

The service does not own:

- canonical clients or accounts;
- canonical transactions;
- Cognito users;
- the central audit chain; or
- public-news/investment data.

Proposed internal components:

```text
contract adapters
activity projection
versioned rules engine
analysis/suppression coordinator
task aggregate and API
Bedrock/template explanation adapter
audit outbox publisher
metrics and health
```

The deployment shape (Lambda, ECS/Fargate, or hybrid) remains an ADR decision.

## Required external contracts

### Matched transaction activity

Required from the transaction owner:

- matched client and account identifiers;
- stable transaction/event identifiers;
- transaction type, decimal amount, currency, timestamp, and status;
- stable synthetic source fingerprint for recurring-credit analysis;
- at-least-once/idempotency semantics;
- six-month historical replay;
- schema compatibility policy; and
- no unmatched/rejected record presented as analysable.

Current state: transaction-service populates matched `account_id` and
`client_id` values through account-service and exposes a private cursor-paged
admin list. RH-SF01 still needs the dedicated service-authenticated, date-bounded
replay contract accepted in ADR-0044, a stable synthetic source fingerprint,
and six months of reproducible synthetic history. Provider-side changes require
Feature 3 owner coordination.

### Client assignment

Required from the client-profile owner:

- resolve client ID to assigned opaque Cognito agent subject;
- active/inactive state;
- assignment version/update time;
- missing/deleted/reassigned behaviour; and
- service authentication.

### Auditor

The merged Auditor consumes Amazon SQS and de-duplicates by `event_id`.

Required from the Auditor owner:

- allow `RELATIONSHIP_HEALTH_SERVICE`;
- accept versioned relationship-task payloads;
- document event/action values;
- maintain idempotency/rejection semantics; and
- provide local contract-test support.

### Bedrock

Required deployment decisions:

- model and inference profile;
- Region/cross-region boundary;
- token/output caps;
- timeout/retry behaviour;
- logging and retention mode;
- IAM permissions; and
- budget/kill-switch controls.

## Internal handoff between Garvit and Swayam

Garvit's detection stream produces one frozen `ClientHealthFinding` contract. Swayam's task stream consumes it through an in-process Java `RelationshipTaskPort` inside the same microservice. No internal HTTP call or queue is used for this handoff.

Contract-owner decision recorded 2026-09-29:

- one finding represents one client and one analysis run;
- every signal qualifying in that run is included;
- each signal has its own baseline/comparison window;
- Garvit's detection stream calculates final signal severity;
- the task stream uses highest signal severity as task priority without rerunning rules;
- Agent ID is excluded and resolved by Swayam's stream through the assignment contract;
- `findingId` provides finding-level idempotency;
- signal fingerprint provides evidence-level de-duplication;
- new evidence attaches to an existing active client task;
- exact aggregates remain inside secured internal evidence but are removed from Bedrock input; and
- unsupported major versions and unknown signal types are rejected.

Swayam must acknowledge the frozen fields/port before SF05 implementation.

SF00 incremental delivery plan:

```text
PR 1: canonical feature plan and proposed contracts
PR 2: Java 25 module and in-process contract records/port
PR 3: JSON Schema and valid fixture validation
PR 4: invalid fixtures and extended invariant tests
```

The later SF00 module is a contract-only Java 25 JAR. It deliberately contains no runnable application, persistence implementation, HTTP controller, AWS provider, or deployment choice.

Local-only verification before incremental PRs:

```text
Java 25 Maven contract tests: 14 passed, 0 failed
JSON Schema: valid combined fixture accepted
JSON Schema: empty signals, caller-controlled Agent ID, and mismatched evidence rejected
```

Required fields:

```text
client ID
analysis-run ID
ruleset version
analysis window
one or more immutable signal-evidence records, each with its own rule window
severity/priority inputs
correlation ID
```

The detection stream must not create task persistence records directly. The task stream must not recalculate signal rules.

## Bedrock data boundary

Permitted derived facts:

- signal/evidence code and rule version;
- observation-window lengths;
- percentage or banded changes;
- transaction counts;
- days since last recurring credit;
- expected cadence; and
- strict output instructions.

Prohibited:

- names/contact details;
- client/account/transaction/agent identifiers;
- account numbers;
- raw transactions;
- descriptions, merchants, or remitter text;
- exact balances or unnecessary exact amounts;
- tokens, internal notes, or audit records.

AI may improve readability only. It does not detect signals or authorize actions.

## Mandatory AI fallback

When Bedrock is disabled, unavailable, timed out, or invalid:

- deterministic signal detection continues;
- a bounded template explanation is generated;
- the task is still created/updated;
- explanation source is `TEMPLATE_FALLBACK`; and
- fallback use is measured and audited.

## Failure behaviour

- Transaction source incomplete/unavailable: do not analyse partial data; retry boundedly and alarm.
- Duplicate input: return/skip the prior outcome idempotently.
- Bedrock failure/invalid output: use template fallback.
- Database failure: roll back task/evidence/outbox together and do not acknowledge work.
- Auditor unavailable: retain outbox records, retry, and alarm on backlog age.
- Assignment unavailable: do not guess an Agent; retain a retryable unassigned result.
- Restart/overlap: use watermarks/leases and produce one logical outcome.

## Usefulness and operational metrics

Product:

- per-rule precision on labelled synthetic scenarios;
- positive-scenario coverage;
- acceptance/completion/dismissal rates;
- tasks per agent/day;
- evidence additions per active task; and
- time to review/disposition.

AI:

- factual consistency;
- schema/unsupported-claim failures;
- fallback rate;
- latency;
- token use; and
- estimated cost.

Reliability:

- duplicate rate;
- analysis success/duration;
- input/audit backlog;
- dependency/retry failure counts;
- API latency/error rate; and
- authorization denials.

MVP invariants:

- zero duplicate tasks in replay/overlap tests;
- zero cross-agent authorization violations;
- zero unsupported facts accepted in the fixed AI evaluation set;
- template fallback succeeds in all forced-AI-outage tests; and
- every audited local state change creates an outbox record.

## Security and privacy

- Synthetic data only.
- OAuth2/IAM least privilege.
- Service-side ownership enforcement.
- Admin read-only policy at API and domain layers.
- Encryption in transit and at rest.
- Secret/configuration services rather than committed values.
- No unnecessary PII in logs, metrics, audit, or AI prompts.
- Bounded validated notes.
- Rate limits, pagination, dependency/security scans, and threat modelling.

## Testing strategy

- Unit: rule boundaries, windows, suppression, lifecycle, authorization, prompt/output validation, fallback, idempotency, outbox.
- Contract: transaction input/replay, assignment lookup, audit envelope, Bedrock fake.
- Integration: migrations, persistence, input-to-task flow, Cognito claims, concurrency, retry/recovery.
- E2E: each signal, combined task, evidence update, insufficient history, unmatched input, Bedrock/fallback, Agent actions, cross-agent denial, Admin read-only, audit outage, duplicate replay.
- Performance/failure: agreed six-month scale, backlog recovery, API latency, redelivery storm, dependency outages.

## Deployment and rollback

Proposed rollout:

1. Accept plan, contracts, and required ADRs.
2. Merge provider-independent domain code/tests.
3. Apply additive migrations in development.
4. Deploy with analysis and Bedrock disabled.
5. Verify API/auth.
6. Replay history in shadow mode.
7. Review metrics.
8. Enable capped task creation.
9. Enable Bedrock after privacy/cost checks.
10. Promote through required PR/environment gates.

Kill switches:

- disable analysis;
- disable task creation;
- disable Bedrock and force templates;
- disable a rule/ruleset; and
- preserve, never discard, pending audit outbox records.

## Cost controls

- Stay within the project's USD 300 hard limit.
- Invoke Bedrock only after deterministic qualification.
- Cap model calls and task generation.
- Cap prompt/output size.
- Measure tokens/cost/fallback.
- Use feature/environment/owner tags.
- Define budget alarms and cleanup procedures before cloud testing.
- Protected USD 100 contingency is not authorized.

## Required architecture decisions

Decisions:

1. service/runtime/compute boundary — accepted in ADR-0034;
2. transaction integration and replay — accepted in ADR-0044;
3. client assignment and service authentication — scoped service-token direction accepted in ADR-0034; assignment contract pending;
4. task API/state/authorization and database topology — health-db and authorization direction accepted in ADR-0034; persistence details pending;
5. Bedrock Region/model/privacy/fallback/cost — Singapore wording-only boundary and fallback accepted in ADR-0034; model limits pending;
6. Auditor SQS contract and transactional outbox — atomic outbox direction accepted in ADR-0034; Auditor contract pending; and
7. observability, deployment, backup/rollback, and cost controls — required controls accepted in ADR-0034; implementation/runbooks pending.

## Agent skills

- Existing project skills: none registered; root `SKILLS.md` is absent.
- Skills to search/evaluate later: AWS architecture, Lambda/ECS review, IAM least privilege, OpenAPI/AsyncAPI contract testing, PostgreSQL migration safety, Bedrock security/cost review.
- No external skill may override project governance, ADR, security, or budget rules.

## Subfeatures and ownership

| ID | Subfeature | Primary owner | Expected components | Dependencies/conflict zones | Branch | Status | Last sync |
|---|---|---|---|---|---|---|---|
| RH-SF00 | Foundation and contract freeze | Garvit | Feature plan, contracts, Java records/port, JSON Schema and fixtures; ADR drafts later | Shared build/config; Swayam acknowledgement required | `task/client-health-monitor/sf00-foundation` | Implemented and merged | 2026-10-16 |
| RH-SF01 | Transaction intake and historical replay | Garvit | `contract/transaction`, ingestion, replay, fixtures | ADR-0044; transaction-owner provider extension; SF00 | `task/client-health-monitor/transaction-intake` | Mock-backed consumer complete and merged; production HTTP/token adapter and Feature 3 E2E pending | 2026-10-21 |
| RH-SF02 | Activity projection and baselines | Garvit | activity model/repository/service | SF01; ADR-0051; health-db migrations | `task/client-health-monitor/activity-projection` | Domain/baselines and V4 projection/coverage JDBC persistence complete and merged | 2026-10-26 |
| RH-SF03 | Versioned signal rules | Garvit | rules config and three rules | SF02; ADR-0052; shared fixtures | `task/client-health-monitor/signal-rules` | V1 recurring-credit, activity-decline and sustained-net-outflow rules complete and merged; Java 25 CI pending | 2026-10-21 |
| RH-SF03-EVAL | Offline labelled rule evaluation | Garvit | rule evaluation harness, labelled synthetic scenarios, evaluation evidence | RH-SF03; ADR-0052 | `task/client-health-monitor/rule-evaluation` | Complete and merged: six labelled positive/negative/boundary/sufficiency scenarios pass deterministically; synthetic-only limitations documented | 2026-10-22 |
| RH-SF04 | Analysis and suppression | Garvit | orchestration, suppression, watermarks | SF02/SF03; ADR-0053; health-db migrations | `task/client-health-monitor/analysis-orchestration` | Deterministic orchestration and V4 durable run/lease/watermark JDBC persistence complete and merged | 2026-10-26 |
| RH-SF05 | Task domain and persistence | Swayam | task aggregate/repository/history/assignment | SF00 finding/ports; ADR-0053 admission policy | `task/client-health-monitor/task-persistence` | Domain, V1/V2 schema, suppression/admission rules, JDBC repository adapter and atomic SF08 task/finding/outbox writes implemented | 2026-10-23 |
| RH-SF06 | Task API and authorization | Swayam | API/security/OpenAPI task paths | SF05; Cognito | `task/client-health-monitor/task-api` | API/runtime wiring, current-assignment reads/actions, opaque keyset pagination, bounded notes, Contract-B consumer and PostgreSQL/runtime integration suites implemented; client-profile Contract-B provider and Cognito provisioning remain pending | 2026-10-23 |
| RH-SF07 | Bedrock explanation and fallback | Swayam | explanation/provider/fallback fixtures | SF00 evidence schema; SF05 attachment port | `task/client-health-monitor/explanations` | Implemented; live enablement pending | N/A |
| RH-SF08 | Audit outbox and adapter | Garvit | redacted deterministic event contract, V3 outbox, atomic task/run enqueue, leased JDBC relay and SQS gateway | ADR-0020/0053; Auditor owner; SF05 task transactions | `task/client-health-monitor/audit-delivery` | JDBC leasing/fencing/retry, conditional SQS gateway, task/signal/run events and scheduler implemented locally; Java 25 Docker CI, IAM/Terraform and Auditor allowlist pending | 2026-10-26 |
| RH-SF09 | Metrics and health | TBD | metrics/health/dashboard/alarms | Hooks across SF01-SF08 | TBD | Unassigned | N/A |
| RH-SF10 | CRM UI integration | TBD | task list/detail/actions/Admin view | Frontend/Azeez/Hayyun coordination | TBD | Unassigned | N/A |
| RH-SF11 | Cloud deployment | TBD | compute/database/IAM/routes/secrets/alarms | Accepted ADRs/platform owner | TBD | Unassigned | N/A |
| RH-SF12 | Integration and release evidence | TBD | E2E/load/runbooks/docs/cost/AI log | All MVP subfeatures | TBD | Unassigned | N/A |

## Independence protocol

1. Garvit authors SF00; Swayam reviews task/assignment/explanation requirements.
2. SF00 merges into `feat/client-health-monitor`.
3. Both developers branch/synchronize from the updated feature branch.
4. Garvit owns SF01-SF04 packages; Swayam owns SF05-SF07 packages.
5. Frozen contracts, migrations, build files, OpenAPI roots, and shared configuration change only through coordinated updates.
6. Each subfeature updates its own tests/docs.
7. SF08-SF12 receive one primary owner each before implementation.

## Pull request size policy

Client Health Monitor implementation PRs must not exceed 500 changed hand-written lines, counting additions plus deletions across:

- production source code;
- test code;
- build and runtime configuration;
- JSON/OpenAPI/event schemas;
- infrastructure as code; and
- hand-written fixtures.

Generated files and dependency lockfiles do not count toward the numeric limit but must still be reviewed for unexpected changes.

Documentation-only PRs are exempt from the numeric limit because the canonical feature plan and contract rationale are intentionally detailed. They must still cover one focused topic.

If an implementation PR would exceed 500 counted lines, split it into independently reviewable task branches/PRs before opening it. Do not hide a large change through generated output, minification, or deferred tests.

Required merge path remains:

```text
task/client-health-monitor/<subtask>
        -> feat/client-health-monitor
        -> dev
        -> staging
        -> main
```

The feature branch never merges directly into `main`.

## Definition of Ready

- [ ] Garvit and Swayam approve scope/non-scope.
- [ ] SF00 contracts reviewed by Swayam.
- [x] Three V1 rule thresholds and labelled scenarios agreed in ADR-0052.
- [ ] Transaction/replay, assignment, and Auditor contracts accepted by owners.
- [ ] Required ADRs accepted with human owners.
- [ ] Bedrock privacy/Region/cost controls accepted.
- [ ] Test, deployment, rollback, observability, and cost plans reviewed.
- [ ] SF08-SF12 ownership assigned before those tasks begin.

## Definition of Done

- [ ] MVP acceptance criteria pass.
- [ ] Agent ownership and Admin read-only behaviour tested.
- [ ] Rules deterministic, versioned, and explainable.
- [ ] Multiple signals produce/update one task.
- [ ] Template fallback works without AWS.
- [ ] Bedrock output cannot introduce unsupported facts.
- [ ] Audit delivery durable and observable.
- [ ] Replay creates no duplicate tasks.
- [ ] Contracts, OpenAPI, schemas, migrations, runbooks, cost and AI records current.
- [ ] CI and lower-environment verification pass.
- [ ] No secrets, real PII, or real banking data included.

## Open questions

1. V1 thresholds/severity are accepted in ADR-0052; deterministic rules expose no confidence percentage.
2. Daily cap, cooldown, worsening and suppression ownership are accepted in ADR-0053.
3. Completion/dismissal reason codes.
4. Transaction integration uses the internal pull/replay API accepted in ADR-0044; Feature 3 owner approval and provider implementation remain pending.
5. Assignment lookup versus assignment event.
6. Lambda/ECS deployment shape and database connection strategy.
7. Bedrock model/Region/retention/logging/cost caps.
8. Auditor event/action/payload contract.
9. SF08-SF12 primary owners.

