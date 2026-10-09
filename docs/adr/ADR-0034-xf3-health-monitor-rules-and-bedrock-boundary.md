# ADR-0034: X-factor 3 health monitor: deterministic rules engine, daily scheduled analysis, Bedrock wording only with template fallback, own health-db

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

X-factor 3, the Client Relationship Health Monitor, detects explainable changes in a client's banking activity and gives the assigned agent a prioritised, auditable follow-up task. It does not claim that a client is leaving, financially distressed or committing fraud. The MVP has three signals, each measured against the client's own history:
- recurring-credit interruption;
- transaction-activity decline;
- sustained net-outflow trend.

At least one x-factor must use AI, and every task must be explainable.

The feature plan [`client-health-monitor_plan.md`](../feature-plans/client-health-monitor_plan.md) (owners Garvit and Swayam) keeps runtime, compute, database, integration, Bedrock and audit decisions open until ADRs exist. This ADR proposes them, for the owners to review.

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
   - an **analysis task** from the same image, started **daily at 08:00 SGT** by EventBridge Scheduler.
2. The analysis task reads matched activity from transaction-service and client-agent assignments from client-service. It calls them through the **internal ALB with a service token**, because a standalone scheduled task cannot use Service Connect.
3. The **Java rules engine** works out each signal and its severity and exact evidence, and handles suppression and de-duplication:
   - one active task per client;
   - a cooldown after a task closes;
   - a daily cap per agent;
   - Pending transactions excluded.
4. **Bedrock in Singapore** only words the verified facts. It receives the signal type, percentage or banded changes, transaction counts, window lengths, the expected cadence, and days since an expected credit. It **never** receives names, client IDs, account numbers, raw transactions, descriptions, exact balances, agent IDs or contact information.
5. If Bedrock fails or returns unsupported content, a deterministic **template** is used.
6. Tasks, signals, evidence, explanations and status history live in its own **health-db**, with an outbox for audit events ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)).

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
- Depends on a transaction activity contract that is still open. The SFTP import now stores `client_id` on matched rows ([ADR-0037](ADR-0037-transactions-database-and-account-linking.md)), but there is no read API for the analysis task yet ([open item 16](../architecture/open-items.md#16-health-monitor-activity-contract)).
- One more RDS instance (about USD 13–15/month single-AZ).
- The feature plan still lists these choices as open, so they need the owners' agreement.

## Security & Privacy Impact

- No client identifiers or raw transactions are sent to the model.
- Agents see only their own clients' tasks; admins can read.
- Every task transition is audited.

## Cost Impact

Bedrock wording calls are small and covered by the USD 5–15/month AI line with a kill switch. The analysis task is billed for minutes per day. health-db adds about USD 13–15/month single-AZ.

## Operational Impact

- Run metrics and an alarm on failed analysis runs.
- The ruleset is versioned in configuration.
- A Bedrock kill switch.

## Validation

- Synthetic histories for each signal produce the expected tasks.
- Repeated runs do not duplicate tasks.
- With Bedrock disabled, tasks are still created with template wording.
- A captured Bedrock request contains no identifiers.

## References

- [X-factor 3: Client Relationship Health Monitor](../architecture/x-factors.md#x-factor-3-client-relationship-health-monitor)
- [Numbered flows 13–13a](../architecture/data-flow.md#numbered-flows)
- [Open item 16](../architecture/open-items.md#16-health-monitor-activity-contract)
- [`client-health-monitor_plan.md`](../feature-plans/client-health-monitor_plan.md) and [`client-health-monitor-contracts.md`](../api/client-health-monitor-contracts.md)
