# ADR-0034: X-factor 3 health monitor: deterministic rules engine, daily scheduled analysis, Bedrock wording only with template fallback, own health-db

- Status: Accepted
- Decision date/time: proposed 2026-10-01 21:50 +08:00; accepted 2026-10-21 17:42 +08:00 (Asia/Singapore)
- Decision owner(s): Garvit
- Original proposal owner: Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (original proposal), Cursor (owner review and acceptance revision)
- Feature/branch: `task/client-health-monitor/transaction-intake`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

X-factor 3, the Client Relationship Health Monitor, detects explainable changes in a client's banking activity and gives the assigned agent a prioritised, auditable follow-up task. It does not claim that a client is leaving, financially distressed or committing fraud. The MVP has three signals, each measured against the client's own history:
- recurring-credit interruption;
- transaction-activity decline;
- sustained net-outflow trend.

At least one x-factor must use AI, and every task must be explainable.

The feature plan [`client-health-monitor_plan.md`](../feature-plans/client-health-monitor_plan.md) names Garvit and Swayam as feature owners. Neo Jia Wen drafted this target architecture for the midterm proposal; Garvit reviewed and accepted the feature-wide decisions below before further implementation.

## Decision Drivers

- Explainability and reproducibility of every task
- AI failure must never stop task creation
- Privacy: no client identifiers sent to a model
- Cost and simplicity

## Options Considered

### Detection

- **Deterministic Java rules engine (chosen).**
- **LLM detection or prioritisation:** not explainable, not reproducible, hard to audit.

### AI use

- **Bedrock for wording only, with a deterministic template fallback (chosen).**
- **No AI:** tasks are harder to read, and at least one x-factor must use AI.
- **AI in the critical path:** an outage would stop task creation.

### Trigger

- **An EventBridge-scheduled analysis task, same image, daily at 08:00 SGT (chosen).**
- **A timer inside the always-on service:** runs in every copy and restarts with each deployment.
- **Hourly or more often:** the 30-day and 90-day windows barely change within a day, so extra runs repeat work.
- **Overnight:** the databases are stopped outside working hours except in the demo week, and agents see new tasks at the start of their day either way.

### Bedrock region

- **Singapore (chosen).**
- **Cross-region inference:** even banded facts would leave the region, for no benefit.

### Storage

- **Own RDS instance, health-db (chosen).**
- **A shared database:** weaker isolation.

## Decision

1. **client-health-monitor-service** has two parts:
   - a Java 25 ECS service with the task API (Contract D, `/api/relationship-health/tasks`: accept, complete and dismiss, with `expectedVersion` optimistic locking);
   - an **analysis task** from the same image, started **daily at 08:00 Asia/Singapore** by EventBridge Scheduler.
   - one run lease prevents overlap; every run has an immutable ID and is idempotent when retried.
2. The analysis task reads matched activity from transaction-service under ADR-0044 and client-agent assignments from client-service. It calls them through the **internal ALB with scoped Cognito client-credentials tokens**, because a standalone scheduled task cannot use Service Connect. Local and contract tests use ports/mocks with the same schemas; mocks never create a second production contract.
3. The **Java rules engine** works out each signal and its severity and exact evidence, and handles suppression and de-duplication:
   - one active task per client;
   - new evidence attaches to an existing `OPEN` or `IN_REVIEW` task;
   - a versioned configurable cooldown follows completion or dismissal;
   - a configurable daily cap per agent defers or suppresses findings with an explicit recorded reason rather than silently discarding evidence;
   - Pending and Failed transactions are excluded from activity and net-flow calculations;
   - the MVP analyses SGD only and fails closed on mixed-currency windows until a conversion policy is accepted;
   - rules, thresholds and severities are versioned and validated against labelled synthetic scenarios.
4. **Bedrock in Singapore** only words the verified facts. It receives the signal type, percentage or banded changes, transaction counts, window lengths, the expected cadence, and days since an expected credit. It **never** receives names, client IDs, account numbers, raw transactions, descriptions, exact balances, agent IDs or contact information. Requests and outputs use strict schemas, bounded token/character limits, timeout and bounded retry controls, and reject facts not present in the request.
5. If Bedrock is disabled, times out, fails, exceeds a limit, or returns invalid/unsupported content, a deterministic **template** is used. Every explanation records its source and template/model version, and a kill switch can force templates.
6. Tasks, signals, evidence, explanations and status history live in a dedicated **health-db**. Flyway applies additive migrations. Task/evidence/history changes and their outbox event commit atomically; a relay sends the outbox to the shared audit queue under [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md). Optimistic locking prevents silent concurrent updates.
7. Agents may read and mutate only tasks assigned to their verified Cognito subject. Admins have cross-agent read-only access. Caller-provided agent identifiers are never trusted.
8. Metrics and alarms cover analysis success/failure/duration, replayed and rejected rows, findings by rule, duplicate/suppressed findings, tasks created, cap usage, Bedrock fallback, and audit-outbox backlog.

## Rationale

- Rules give reproducible, explainable tasks that can be audited.
- AI improves readability without making decisions.
- The fallback keeps the feature working when AI is off.
- A daily task matches how slowly the signals change.

## Consequences

### Positive

- Every task shows why it exists: the evidence, the rule version and the windows.
- Bedrock spend is small and optional.
- AI outages do not affect task creation.

### Negative / Trade-offs

- Rules only find what they are written for; thresholds need tuning on synthetic data.
- ADR-0044 fixes the consumer contract, but production enablement still depends on the Feature 3 owners implementing the authenticated replay endpoint, source fingerprint and six-month synthetic history. Until then, tests use the contract-faithful mock provider.
- A dedicated database adds cost and operational ownership; environment lifecycle and Multi-AZ timing follow the project's accepted cost/deployment controls rather than assuming permanent Multi-AZ.
- Rule thresholds, cooldown duration, worsening criteria and cap values still require labelled synthetic evaluation; this ADR fixes their governance and failure behaviour, not arbitrary values.

## Security & Privacy Impact

- No client identifiers or raw transactions are sent to the model.
- Agents see only their own clients' tasks; admins are read-only.
- Background callers use least-privilege service scopes over TLS; network location is never sufficient authentication.
- Every task transition and suppression outcome is audited without raw descriptions or unnecessary PII.

## Cost Impact

Bedrock wording calls are small and covered by the project AI budget with a kill switch. The analysis task is billed for minutes per day. The health database follows the environment lifecycle and backup controls in the target architecture; its actual Terraform plan must be added to the resource register and remain within the USD 300 hard limit.

## Operational Impact

- EventBridge Scheduler uses the `Asia/Singapore` timezone and invokes one bounded task.
- Run leases and immutable run IDs make retries and overlap safe.
- The ruleset, explanation templates and model configuration are versioned.
- Bedrock, task creation and individual rules have independent kill switches.
- Flyway migrations, backup/restore, retention and rollback instructions are required before deployment.
- Repeated replay failures, analysis failures and aged outbox rows alarm with a named owner.

## Validation

- Synthetic histories for each signal produce the expected tasks.
- Repeated runs do not duplicate tasks.
- Overlapping runs cannot both publish findings.
- Incomplete replay, mixed currencies and insufficient history fail closed.
- One active task receives new evidence; cooldown/cap suppression is explicit and auditable.
- With Bedrock disabled, tasks are still created with template wording.
- A captured Bedrock request contains no identifiers.
- Wrong-agent mutations and Admin mutations are rejected.
- Task, history and outbox records roll back together on database failure.

## References

- [X-factor 3: Client Relationship Health Monitor](../architecture/x-factors.md#x-factor-3-client-relationship-health-monitor)
- [Numbered flows 13–13a](../architecture/data-flow.md#numbered-flows)
- [`client-health-monitor_plan.md`](../feature-plans/client-health-monitor_plan.md) and [`client-health-monitor-contracts.md`](../api/client-health-monitor-contracts.md)
- [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)
- [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md)
- [ADR-0028](ADR-0028-cicd-migrations-terraform.md)
- [ADR-0044](ADR-0044-client-health-transaction-intake-and-replay.md)

## Revision history

- 2026-10-01: Neo Jia Wen drafted the target architecture for the midterm proposal.
- 2026-10-21: Garvit reviewed and accepted the architecture, updated ownership, aligned transaction intake with ADR-0044, and added explicit run safety, rule/currency governance, authorization, AI validation/fallback, atomic audit delivery and observability controls.
