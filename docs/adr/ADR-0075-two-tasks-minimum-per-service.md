# ADR-0075: Every service runs at least two tasks

- Status: Accepted (2026-11-03; decided by Neo Jia Wen)
- Decision date/time: 2026-11-03 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/evaluation-accounts-and-min-tasks`
- Related issue/PR: TBD
- Supersedes: the one-task minimum in the crm stack's `locals.tf` and ADR-0051 decision 1's "at least 1 task"
- Superseded by: N/A

## Context

Each ECS service ran one task while the stack was up, and auto scaling added more under load (up to 6; 2 for engagement-service). On 2026-11-02 the task-failure drill stopped client-service's only task: agents got HTTP 503 for 59 seconds, and the service was healthy behind the ALB again only after 1 minute 41 seconds (`docs/testing/availability-drills-2026-11-02.md`). NFR 2 asks for high availability, and the report has to show that the design survives the loss of a component, not only that it recovers.

The deployment runs only for test and evaluation windows and is torn down afterwards (ADR-0058), so the extra tasks are paid for only while it runs.

## Decision Drivers

- No user-visible outage when one task fails or is replaced.
- Survive the loss of one availability zone's tasks.
- Keep the cost bounded during deployment windows.

## Options Considered

### Option A: keep one task and rely on ECS replacing it

- Pros: lowest cost.
- Cons: about a minute of 503s for every task failure, deployment or zone failure, as measured.

### Option B: two tasks only in `high_availability` mode

- Pros: the cost only in the demo week.
- Cons: every other deployment, the evaluation included, still has the outage; one more difference between modes to test.

### Option C: at least two tasks for every service, in every mode (chosen)

- Pros: a task can fail with the other still serving; Fargate spreads the two tasks across the two app subnets, one in each zone.
- Cons: about nine more tasks of 0.25–1 vCPU while the stack runs.

## Decision

1. While the CRM stack runs, every ECS service runs at least 2 tasks: the crm stack's services, dashboard-service and engagement-service. Auto scaling still adds tasks up to each service's maximum (6, or 2 for engagement-service).
2. engagement-service therefore runs exactly 2 tasks. ADR-0051 keeps its maximum at 2, because more tasks would not send more mail.
3. Stopping the stack still scales every service to 0 (ADR-0029).
4. Scheduled one-off tasks (the Client Health analysis run, db-migrate, db-console) are unchanged.

## Rationale

The drill showed the outage is the time ECS needs to start and register a replacement, which no tuning removes. A second task already registered with the ALB removes it. Every service already runs safely with several tasks, because auto scaling could already start more: the outbox relays and the mail dispatcher use leased rows with `SKIP LOCKED`, the root-admin seed tolerates a race, and the auditor's hash chain held through the 2026-11-02 load test with two auditor tasks.

## Consequences

### Positive

- One task's failure, a deployment or a zone's loss leaves the service answering.
- The task-failure drill can show no errors, not just recovery.

### Negative / Trade-offs

- Higher Fargate cost while the stack runs.
- Two tasks of a service now always share its database connections; each service's pool is sized for six tasks already.

## Security & Privacy Impact

None.

## Cost Impact

Nine more tasks while running (four Go services at 0.25 vCPU, three Java services at 0.5 vCPU, dashboard-service and engagement-service at 1 vCPU): about USD 0.28 an hour at ap-southeast-1 on-demand Fargate prices (USD 0.05056 per vCPU-hour, USD 0.00553 per GB-hour), or about USD 2.30 for an 8-hour test day. Nothing while torn down.

## Operational Impact

Deployments roll one task at a time with one always serving. The runbook's expected task counts change from 1 to 2.

## Validation

- `terraform test` in the crm, business-dashboard and agent-engagement stacks asserts the minimum.
- Re-run the task-failure drill (`ECS Task Failure Test.mp4`): stopping one client-service task should give no 5xx to the poller.

## References

- [Availability drills, 2026-11-02](../testing/availability-drills-2026-11-02.md), [ADR-0018](ADR-0018-ecs-fargate-compute.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0051](ADR-0051-engagement-service-and-mail-dispatcher.md), [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md)
