# ADR-0051: Agent engagement in its own service and database, with a lease-based mail dispatcher

- Status: Accepted
- Decision date/time: 2026-10-21 11:04 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/agent-engagement/plan`
- Related issue/PR: TBD
- Supersedes: N/A. Extends [ADR-0017](ADR-0017-microservices-per-business-area.md) (adds a service) and [ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md) (adds a database)
- Superseded by: N/A

## Context

The [agent engagement feature](../feature-plans/agent-engagement_plan.md) gives agents:

- calendar events, which can be linked to their own clients;
- iCalendar invites to their own mailbox ([ADR-0052](ADR-0052-calendar-invites-by-email.md));
- reminder emails before meetings;
- AI-drafted follow-up emails to clients that they review and send ([ADR-0053](ADR-0053-email-delivery-through-ses.md), [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md)).

This needs four things the CRM does not have in one place:

1. Durable state: events, queued mail, drafts, holidays and preferences.
2. Work that runs without a request, because reminders fall due at a set time.
3. Outbound calls to SES, Bedrock and Cognito.
4. Writes that must be audited ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)).

Existing constraints:

- dashboard-service is stateless by decision ([ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md) decision 1). It holds no credentials, and it can be rolled back by scaling to zero because nothing depends on it.
- The CRM uses one service and one database per business area ([ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md)).
- Services run on ECS Fargate with one to six tasks ([ADR-0018](ADR-0018-ecs-fargate-compute.md)). Anything a service runs on a timer must therefore be safe when more than one task is running.
- Between sessions, every service scales to zero and the NAT gateways are removed ([ADR-0042](ADR-0042-terraform-deployment.md) decision 4).
- Normal spend must stay under USD 300, and about USD 94 of headroom is left ([budget](../cost/budget.md)).

## Decision Drivers

- Data ownership: one owner for events, drafts and queued mail.
- No mail sent twice by two tasks.
- No change to dashboard-service's guarantees.
- Cost: the smallest footprint that keeps isolation.
- One developer builds and runs it, so the moving parts must be few.
- Java 25 is the default runtime (`project_plan.md` §2.2).

## Options Considered

### Where the feature runs

- **Extend dashboard-service.**
  - Pros: no new ECS service.
  - Cons: it supersedes ADR-0033. The dashboard would gain a database, writes, secrets and outbound access. Rolling back the dashboard would also stop reminders. Its read path and the mail job would scale together, even though they have nothing in common.
- **A new service, `engagement-service` (chosen).**
  - Pros: clear ownership. Its own failure and rollback. The dashboard is untouched.
  - Cons: one more ECS service, database, Terraform stack and pipeline.
- **Several services (calendar, notifications, drafting).**
  - Pros: each part could scale and deploy alone.
  - Cons: about three times the fixed cost, plus calls between services for data that belongs together (reminders point at events, drafts point at holidays and events). There is no load that needs it.

### Where its data lives

- **Its own RDS PostgreSQL instance, `db.t4g.micro` (chosen).** Follows ADR-0023 and isolates it fully. About USD 0.02–0.03 an hour while running.
- **A database on a shared x-factor instance.** About USD 13–15 a month cheaper if run 24/7, with weaker isolation. Kept as the fallback the budget already lists.
- **DynamoDB.** Cheap when idle, but due-time queries, optimistic locking and the outbox pattern the other services use would all be new.

### How queued mail is sent

- **A dispatcher inside the service, claiming rows with a lease (chosen).**
  - Pros: no extra infrastructure. Safe with any number of tasks. Can be tested against Postgres.
  - Cons: sends only while the service runs.
- **A separate worker-mode ECS service from the same image** ([ADR-0007](ADR-0007-alert-service-runtime-boundary.md) pattern).
  - Pros: the API and the worker scale apart.
  - Cons: one more task to pay for, for a few emails a day.
- **EventBridge Scheduler, one schedule per reminder, calling a Lambda.**
  - Pros: fires even while the stack is stopped.
  - Cons: a second runtime and deployment. Every event change must update or delete schedules, and the Lambda still needs the agent's address and SES.
- **A scheduled ECS task every minute.**
  - Pros: none over the in-service loop.
  - Cons: a cold start every minute, and it costs more.
- **A Postgres advisory lock so only one task dispatches.** Not needed: row leases already stop double claims. Kept in reserve in case a later channel needs a single reader, for example Telegram's `getUpdates`.

## Decision

1. **engagement-service** is a Java 25 Spring Boot service on ECS Fargate.
   - It is deployed by its own Terraform stack, `stacks/agent-engagement`, built like the business dashboard's ([ADR-0047](ADR-0047-business-dashboard-terraform-stack.md)).
   - It runs at least 1 task and at most 2, at 1 vCPU and 2 GB (0.5 vCPU starts too slowly, per [ADR-0042](ADR-0042-terraform-deployment.md) Cost Impact).
   - It owns **`engagement-db`**: PostgreSQL on its own `db.t4g.micro`. That database is single-AZ even in the high-availability week. Flyway migrations run in an init container, and the service connects as a login without schema rights ([ADR-0028](ADR-0028-cicd-migrations-terraform.md)).
2. **Modules.** The service has six modules: `calendar`, `reminders`, `notifications`, `drafting`, `holidays` and `audit`.
   - Each module reads and writes only its own tables, enforced by an architecture test.
   - No other service reads `engagement-db`.
3. **Callers.** Only the frontend calls engagement-service, through `/api/engagement/*` behind API Gateway's `/api` route and the internal ALB. dashboard-service does not change.
4. **Dispatcher.** Every task runs it every 60 seconds:
   1. In a short transaction, it claims up to 25 `mail_queue` rows that are `PENDING`, due, and either unleased or past their lease. It uses `SELECT … FOR UPDATE SKIP LOCKED` and sets `leased_until` to now + 2 minutes.
   2. Outside any transaction, it sends each row in turn, no faster than SES allows ([ADR-0053](ADR-0053-email-delivery-through-ses.md)).
   3. It records each result in its own transaction:
      - success becomes `SENT` with the SES message ID;
      - an error is retried at 1, 5 and 15 minutes, and becomes `FAILED` after 3 attempts;
      - a reminder whose event has already started becomes `MISSED` without sending;
      - a row for a cancelled event becomes `CANCELLED`.
5. **Delivery is at least once.** A task that dies after SES accepts a message, but before the result is recorded, leaves a lease that expires, and the row is sent again. Two running tasks never claim the same row.
6. **Stop model.** No mail is sent while the stack is stopped. When it starts, due rows are processed by the rules in decision 4. A reminder for a meeting that has not started yet is sent late; one for a meeting that has started is `MISSED`.
7. **Writes and audit.** Event writes, their queued mail and their audit outbox rows are saved in one transaction. The audit outbox follows [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md), using event-alert-service's lease-based relay (`AuditOutboxLeaseService`, `AuditOutboxPublisher`) as the pattern.

## Rationale

A new service keeps ADR-0033's guarantees intact and matches how the CRM already splits business areas. One deployable service with internal modules gives the separation that matters (each module owns its own tables and sits behind its own interface) without the cost of several ECS services and databases. Leased rows with `SKIP LOCKED` are the same pattern the audit relays already use. They make the dispatcher safe with two tasks and need nothing besides the database the service already has. The stop-model limit is acceptable because the CRM itself is offline at those times, and the demo runs while the stack is up.

## Consequences

### Positive

- The dashboard and the CRM's core services are untouched. Rolling this feature back cannot break them.
- Mail survives SES or service failures. It stays queued and is retried.
- The modules can later be split out (for example, `notifications` as a shared service) by replacing one interface with a queue.

### Negative / Trade-offs

- One more service, database, stack and pipeline to keep running.
- Reminders are not sent while the stack is stopped, so the feature is not usable as a real reminder system outside sessions.
- At-least-once delivery: a rare duplicate invite or reminder is possible after a crash.
- The API and the dispatcher share a task, so a burst of sending competes with API requests. At this volume that does not matter; decision 1's limit of 2 tasks keeps it bounded.

## Security & Privacy Impact

- engagement-service gets only the permissions it needs, through its task role:
  - `ses:SendRawEmail` on its identity;
  - `bedrock:InvokeModel` on one inference profile and its model ([ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md));
  - `cognito-idp:ListUsers` on the user pool;
  - `sqs:SendMessage` on `audit-insert`;
  - read access to its own database secret.
- dashboard-service gains nothing.
- `engagement-db` holds agent-typed titles and descriptions that may name a client, client IDs, draft text with placeholders, and no email addresses. It is encrypted at rest with KMS like the other databases.
- Client crypto-shredding ([ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md), [ADR-0030](ADR-0030-client-data-envelope-encryption.md)) does not reach this database. Plan open question Q6 tracks this.
- Its security group accepts traffic only from the internal ALB. It may reach client-service over Service Connect, and SES, Bedrock, Cognito and SQS through the NAT gateway.

## Cost Impact

Prices for ap-southeast-1, to be checked in the AWS Pricing Calculator.

- **ECS:** 1 task × 1 vCPU / 2 GB, about USD 0.06 an hour while running. About USD 4–7 over the planned lifecycle of about 60 prod hours and one 48-hour staging test.
- **RDS:** `db.t4g.micro`, single-AZ, 20 GB gp3, about USD 0.02–0.03 an hour plus storage. About USD 3–6 over the same lifecycle.
- **Fallback:** a database on a shared x-factor instance removes most of the RDS share.
- **Risk:** leaving the service running after the CRM is stopped costs about USD 1.50 a day, the same as the dashboard.

## Operational Impact

- A new stack, `stacks/agent-engagement`, is applied after `stacks/crm` and destroyed before it. [deploy-aws.md](../runbooks/deploy-aws.md) gets the steps.
- The stack is built like ADR-0047's: a remote state read of an output from the CRM stack, its own ECR repository, security group, ALB rule and self-signed TLS certificate, and allow-listing on the audit queue.
- CloudWatch metrics:
  - mail sent, failed and missed, by kind;
  - dispatcher lag (age of the oldest due `PENDING` row).
- An alarm fires when lag is over 5 minutes while running ([ADR-0027](ADR-0027-observability-baseline.md)).
- Backups use AWS Backup like the other databases. Restoring brings back queued mail, and rows already past their meeting are marked `MISSED`.
- Rollback: scale to zero or destroy the stack. Nothing else calls the service.

## Validation

- An architecture test fails if one module touches another module's tables.
- Repository tests on Postgres (Testcontainers):
  - two dispatchers claiming at the same time never claim the same row;
  - an expired lease is claimed again;
  - a row for a started meeting becomes `MISSED`;
  - retries back off and stop after 3 attempts.
- Kill a task mid-send in staging, then confirm the row is sent after the lease expires.
- In AWS, with 2 tasks running, create 20 events with reminders and confirm each reminder arrives exactly once.

## References

- [Agent engagement feature plan](../feature-plans/agent-engagement_plan.md)
- [ADR-0007](ADR-0007-alert-service-runtime-boundary.md), [ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0018](ADR-0018-ecs-fargate-compute.md), [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md), [ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md), [ADR-0027](ADR-0027-observability-baseline.md), [ADR-0028](ADR-0028-cicd-migrations-terraform.md), [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md), [ADR-0042](ADR-0042-terraform-deployment.md), [ADR-0047](ADR-0047-business-dashboard-terraform-stack.md)
- PostgreSQL documentation: `SELECT … FOR UPDATE SKIP LOCKED`

## Revision history

- 2026-10-21 11:04 +08:00: proposed.
- 2026-10-21: accepted by the decision owner.
