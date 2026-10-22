# ADR-0048: The alert worker in its own Terraform stack

- Status: Accepted
- Decision date/time: 2026-10-20 (Asia/Singapore)
- Decision owner(s): Hayyun (proposer), confirmed accepted 2026-10-21 after team discussion with Azeez (owner of EDA-D1-02) and Neo Jia Wen (infrastructure owner)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/event-driven-alerts/search-infra`
- Related issue/PR: TBD
- Supersedes: N/A. Follows the layout of [ADR-0047](ADR-0047-business-dashboard-terraform-stack.md) and fills the gap [ADR-0042](ADR-0042-terraform-deployment.md) leaves for EDA-D1-02 (INF-06 in the infrastructure plan)
- Superseded by: N/A

## Context

The event-alert service has two modes from one image ([ADR-0007](ADR-0007-alert-service-runtime-boundary.md)): the API, which the CRM stack already deploys, and a run-once worker that searches public news for one window and exits ([ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md) decision 1). Nothing starts the worker today. The CRM stack's `services.tf`, `scheduler.tf` and `audit.tf` each say the worker's schedule, Bedrock access and AgentCore access belong to EDA-D1-02, and the CRM stack already exposes an `alerts_worker_inputs` output for it.

The worker needs the CRM's cluster, private subnets, alerts security group, alerts-db and its secret, the project's image, and the alarm topic. It needs no new network path: it reaches alerts-db, and reaches AWS APIs through the NAT gateway, the same way the API task does. It sends nothing to the audit queue itself, because the API process publishes the shared audit outbox, including rows a worker run leaves behind.

Two things about it are still unknown. The Tokyo AgentCore gateway's resources depend on the live spike (open item 14), and the exact IAM resource ARNs for a Bedrock global inference profile have not been checked against AWS documentation.

## Decision Drivers

- Follow how the CRM stack and the business dashboard are already built and deployed by hand, so the same runbook and the same `crm-infra-admin` access work.
- Changing the worker must not mean planning and applying the CRM stack, which also holds the databases, Cognito and the audit chain ([ADR-0047](ADR-0047-business-dashboard-terraform-stack.md) context).
- The schedule must not run while the stack is stopped ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)).
- Least privilege: Bedrock and AgentCore permissions exist only when those capabilities are switched on.
- Ingestion and AI stay off until their own gates pass ([ADR-0010](ADR-0010-alert-ai-provider-and-safety.md), ADR-0031).
- No resource whose configuration we cannot yet verify.

## Options Considered

- **Put the worker in `stacks/crm` behind an `enable_alert_worker` switch.** One state and no output wiring. Rejected: every worker change plans the whole CRM stack, it adds to files the infrastructure plan already calls a conflict zone, and it repeats the problem ADR-0047 removed for the dashboard.
- **A new stack, `stacks/alert-worker`, that reads the CRM stack's `alerts_worker_inputs` output (chosen).** The same pattern as `stacks/business-dashboard`. The CRM stack gains only extra keys on an output that already exists.
- **Create the Tokyo AgentCore gateway in this stack now.** Rejected for now: the spike has not run, so the resource types, the web search target and the connector settings are unconfirmed, and a second provider in Tokyo would be added on a guess. The stack takes the gateway ARN as an optional variable instead.

## Decision

1. **A new stack, `infrastructure/terraform/stacks/alert-worker/`,** reads `crm/<legal_entity>/<environment>.tfstate` through `terraform_remote_state` and its `alerts_worker_inputs` output. Apply the CRM stack first and destroy this one first.
2. **The CRM stack's `alerts_worker_inputs` output gains keys and loses none:** `name_prefix`, `region`, `cluster_name`, `alerts_db_name`, `image`, `cpu_architecture`, `use_spot`, `log_retention_days`, `running` and `deletion_protection`. Renaming or removing a key breaks this stack. This is the only change to the CRM stack.
3. **The worker is a scheduled ECS task, not a service,** made with the existing `ecs-service` module (`create_service = false`), as chain-verify is. It uses the event-alert-service image and tag the CRM stack deploys, with `ALERT_MODE=WORKER`, 0.5 vCPU and 1 GB, in the private subnets and the existing alerts security group. That group already reaches alerts-db and the internet through the NAT, so no security group rule changes. The worker's audit publisher is off.
4. **EventBridge Scheduler starts it every four hours** (`rate(4 hours)`, Singapore time), with its own scheduler role limited to running that task definition in that cluster and passing its two roles. Scheduler retries are off, because a retry would repeat the searches and the next run resumes from the watermark. The schedule is `ENABLED` only when the CRM stack's `running` is true **and** `ingestion_enabled` is true. Otherwise it is `DISABLED`: with ingestion off a run exits at once as `DISABLED`, so starting it would only cost task starts. After a CRM apply that changes `running`, apply this stack again.
5. **A failed run raises an alarm.** The worker exits 1 when its window fails, so an EventBridge rule on "ECS Task State Change" with a non-zero exit code for this task family publishes to the CRM stack's alarm topic, as the chain-verify rule does (ADR-0031: an alarm on failed runs).
6. **The task role gets only what the switched-on capabilities need,** decided by variables so the policy names are known at plan time:
   - `ai_enabled`: `bedrock:InvokeModel` on the pinned `global.openai.gpt-5.6-luna` inference profile in `bedrock_region`, and on the foundation model only when called through that profile.
   - `ingestion_enabled` and `agentcore_gateway_arn` set: `bedrock-agentcore:InvokeGateway` on that gateway.
   With both off the task role has no extra permissions beyond the database secret and logs.
7. **Both switches default to off,** as the service does. Turning ingestion on before the AgentCore client exists makes every run fail with `PROVIDER_NOT_CONFIGURED` and exit 1. That is used deliberately as the smoke test of the schedule, the task and the alarm.
8. **The Tokyo AgentCore gateway and its web search target are not created here.** They are added, in this stack or a small separate one, by a later ADR after the live spike. Until then `agentcore_gateway_arn` stays null.
9. **No monthly quota, budget or kill-switch state lives in Terraform.** Those are in alerts-db ([ADR-0014](ADR-0014-alert-observability-cost-and-kill-switches.md) amendment); the AWS Budgets in `bootstrap` remain authoritative for spend.

## Rationale

This is the layout the team already chose for a leaf service in ADR-0047, and the infrastructure plan (INF-06) already expects the alert worker to be "alerts' own Terraform" reading `alerts_worker_inputs`. Reusing the `ecs-service` module, the scheduler-role shape and the failed-exit rule keeps it close to chain-verify, so a reviewer can compare them line by line. Gating the schedule on both `running` and `ingestion_enabled` stops it spending anything until the feature is genuinely switched on.

## Consequences

### Positive

- The worker can be changed, applied and destroyed without touching the CRM stack's state.
- With the defaults it creates a task definition, a disabled schedule, a rule and two IAM roles, and nothing runs.
- The failed-run alarm and the schedule can be tested before the AgentCore client exists.

### Negative / Trade-offs

- A fifth state to apply and destroy, and a runbook step. The stack depends on the CRM state's output keys.
- The schedule does not follow the CRM `running` flag by itself: it needs another apply here after a start or stop (as the dashboard's tasks do).
- The worker shares the API's security group, so the two cannot be given different network rules without a later change.
- The Bedrock IAM resource ARNs in decision 6 are written from the AWS model for global inference profiles and have not been run against a real account. Task 2 of the validation below checks them.

## Security & Privacy Impact

- No credentials in the stack: the database login is read from the existing Secrets Manager secret, and no AWS key is stored.
- Only public search terms leave the private boundary (ADR-0031); this stack adds no data path for client or holding data.
- The scheduler role can only start this task definition in this cluster and pass its roles.
- Bedrock and AgentCore permissions are absent unless their switches are on.

## Cost Impact

At 0.5 vCPU and 1 GB, a run of about two minutes six times a day is about 6 task-hours a month, roughly **USD 0.15 a month** at Singapore Fargate rates (to be confirmed against the bill). EventBridge Scheduler's free tier covers the invocations. Log storage is small and expires at the CRM's 30 days. Search and model charges are bounded by the quotas and the USD 25 monthly stop in [the event-alert budget](../cost/event-driven-alerts-budget.md), and the `crm-bedrock` budget in `bootstrap` alerts on them. No new NAT gateway, database or load balancer is added.

## Operational Impact

- Deploy: the runbook's new step, after the CRM stack is running and its images are pushed ([event-alert operations runbook](../runbooks/event-driven-alerts-operations.md#deploy-the-worker)).
- Run once by hand: the `run_once_command` output.
- Alarm: a non-zero exit reaches the CRM alarm topic's e-mail subscription.
- Teardown: destroy this stack before the CRM stack. It owns no data.

## Validation

- `terraform test` in the stack, against a mocked provider and a stand-in for the CRM state: the schedule is `DISABLED` with ingestion off or the CRM stack stopped and `ENABLED` when both are on; the task is a scheduled task with no service; runs are Singapore-time, never retried and in private subnets; the failure rule reports to the CRM topic. The CRM stack's own seven tests still pass.
- On a real account, with `ingestion_enabled = true` and no AgentCore client: a by-hand run exits 1 with `PROVIDER_NOT_CONFIGURED`, the alarm e-mail arrives, and `search_run` shows one `FAILED` row. Then set `ingestion_enabled = false` again.
- On a real account, with `ai_enabled = true`: a call to the pinned profile succeeds with the policy in decision 6, or the policy is corrected and this ADR amended.

## References

- [ADR-0007](ADR-0007-alert-service-runtime-boundary.md), [ADR-0010](ADR-0010-alert-ai-provider-and-safety.md), [ADR-0014](ADR-0014-alert-observability-cost-and-kill-switches.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md), [ADR-0042](ADR-0042-terraform-deployment.md), [ADR-0047](ADR-0047-business-dashboard-terraform-stack.md)
- [Alert gaps](../feature-plans/event-driven-alerts_gaps.md) (gap 2: the AgentCore client does not exist yet)
- [Infrastructure plan](../feature-plans/infrastructure_plan.md) INF-06, [event alerts plan](../feature-plans/event-driven-alerts_plan.md) EDA-D1-02
- [`infrastructure/terraform/stacks/alert-worker/`](../../infrastructure/terraform/stacks/alert-worker/)

## Revision history

- 2026-10-20: proposed.
- 2026-10-21: accepted by Hayyun after discussion with Azeez and Neo Jia Wen.
