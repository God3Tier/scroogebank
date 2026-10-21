# Agent Engagement — Feature Plan

- Feature name: `agent-engagement`
- Shared feature branch: `feat/agent-engagement`, from `dev` at `8013775`
- Subtask branch prefix: `task/agent-engagement/<subtask>`
- Status: **In progress.** Required ADRs (§19) are accepted (2026-10-21), the team accepted the feature (Q1, 2026-10-21), and the work is split across four task branches (§20): service, auditor, frontend and infrastructure. Live AWS checks (AE-T11) follow once all four are merged.
- Primary developer: **Neo Jia Wen** (all tasks, §20)
- Plan last updated: 2026-10-21
- Decisions: [ADR-0051](../adr/ADR-0051-engagement-service-and-mail-dispatcher.md) (service, database, dispatcher), [ADR-0052](../adr/ADR-0052-calendar-invites-by-email.md) (calendar invites), [ADR-0053](../adr/ADR-0053-email-delivery-through-ses.md) (SES), [ADR-0054](../adr/ADR-0054-ai-follow-up-drafts-and-holiday-data.md) (AI drafts, holidays). All Accepted 2026-10-21. Builds on ADR-0017, ADR-0020, ADR-0023, ADR-0025, ADR-0030, ADR-0035, ADR-0036, ADR-0042.
- Contract: [`docs/api/engagement-api.yaml`](../api/engagement-api.yaml) (implemented by `services/engagement-service`)
- Drafted with AI assistance (Claude Code). Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.

## 1. Problem statement

The CRM records clients, accounts and transactions, but gives agents no help managing their contact with clients. Agents keep meetings in a separate calendar, have nothing to remind them before a client meeting, and write every follow-up email from scratch. Agents also miss easy reasons to get in touch, such as a public holiday coming up, a branch closure, or a good time for a review before year end.

## 2. User and business value

- **Agents:** one place to plan client meetings, which also appear in their own mail calendar; email reminders before meetings; a ready-to-edit follow-up email for a chosen client in seconds.
- **Bank:** more consistent, timely and documented client contact. Every generated and sent email is audited, and a person always approves what is sent.
- **Course:** a distinct feature that integrates with outside systems (mail calendars through iCalendar, email through SES) and uses AI within a strict privacy boundary.

### Distinction from other features

- **Client Health Monitor (Garvit, Swayam):** decides *who* needs attention and *why*, from transaction signals, and creates follow-up tasks. This feature handles *how* and *when* an agent makes contact. It does not create follow-up tasks or read transaction signals.
- **Event-Driven Alerts (Azeez, Hayyun):** market news against holdings. This feature has no market data.
- **Business dashboard (x-factor 2):** read-only figures. Unchanged by this feature.

## 3. Scope

1. `services/engagement-service`: Java 25, Spring Boot, its own PostgreSQL database (ADR-0051).
2. **Calendar events:** an agent creates, updates and cancels single (non-recurring) events. An event may link to one of the agent's own clients.
3. **Calendar integration:** each create, update or cancel emails the agent an iCalendar invite, update or cancellation, which Gmail and Outlook add to the agent's calendar (ADR-0052).
4. **Meeting reminders:** up to two reminder emails per event at chosen offsets, sent by an in-service dispatcher (ADR-0051, ADR-0053).
5. **Public holidays:** a versioned holiday table, starting with Singapore 2026–2027 (ADR-0054).
6. **AI follow-up drafts:** the agent picks a client and a purpose. Bedrock writes a draft from placeholders and public holiday data only, with a template fallback (ADR-0054).
7. **Sending client emails:** the agent reviews and edits a draft, then sends it through SES (ADR-0053).
8. Audit events for every write, generate and send, plus the auditor's support for them.
9. Frontend: calendar page, event form, draft editor, an "Upcoming meetings" panel on the agent home page, and notification preferences.
10. Terraform stacks `stacks/agent-engagement` (per session) and `stacks/mail-identity` (long-lived SES identity), runbook steps, tests and docs.

## 4. Explicit non-scope

- **Telegram, SMS or push delivery.** Telegram is deferred; adding it later needs its own ADR.
- **Two-way calendar sync.** No Google Calendar API, no Microsoft Graph, no OAuth tokens, and no reading of the agent's existing calendar.
- **Inviting clients to meetings.** Invites go to the agent only.
- **Recurring events, attachments and shared calendars.**
- **Sending without approval.** Nothing is ever sent automatically. Every client email needs the agent's explicit send.
- **Free text to the model.** The agent's own notes are never sent to the model.
- **Advice in emails.** No product, rate, price, investment or performance content in drafts.
- **Bulk email.** One client per draft.
- **Replies.** No inbound email, reply tracking or open tracking.
- **Admin management of agents' calendars or drafts** (§10).
- **Health monitor data.** No use of the health monitor's signals or evidence in drafts.
- **Storing sent mail.** Resolved email bodies and recipient addresses are not stored after sending (§15).

## 5. Acceptance criteria

1. An agent can create, update and cancel an event, optionally linked to one of their own clients. Linking another agent's client is refused, proven by tests.
2. Within about 2 minutes of a create, update or cancel, while the stack is running, the agent's mailbox receives an invite, update or cancellation. In Gmail and Outlook it adds, moves or removes the calendar entry.
3. Each reminder arrives within 2 minutes of its due time while the stack is running. A reminder whose event has already started is marked `MISSED` and never sent.
4. With two service tasks running, no invite or reminder is sent twice, proven by a concurrency test.
5. An agent can generate a draft for their own client. The request sent to the model contains no client name, email, ID or other personal data, proven by a test on the captured request. Invalid or unsafe model output gives the template draft, and the draft shows which holiday it used.
6. A draft is sent only when the agent explicitly sends it, and only once. A second send returns 409.
7. An agent cannot read or act on another agent's events or drafts by any ID, proven by tests.
8. Every create, update, cancel, generate, send and discard produces an audit event that the auditor accepts.
9. AI drafting is off unless enabled. The kill switch and the daily and monthly caps are enforced.
10. Billable resources added: one ECS service, one `db.t4g.micro` RDS instance and one SES identity, nothing else. Cost stays within §14.

## 6. Architecture

One deployable service, with internal modules that each touch only their own tables (ADR-0051):

| Module | Responsibility |
|---|---|
| `calendar` | Events, client-ownership check, iCalendar generation |
| `reminders` | Due-time calculation, rescheduling when an event changes |
| `notifications` | Mail queue, dispatcher, `MailChannel` port with an SES adapter |
| `drafting` | `DraftGenerator` port: a Bedrock adapter, a template fallback, a safety screen, placeholder filling |
| `holidays` | Read-only holiday table and lookup |
| `audit` | Transactional outbox and relay to the shared `audit-insert` queue (ADR-0020) |

- **Calls in:** the frontend calls `/api/engagement/*` through API Gateway's `/api` route and an ALB rule. Only Cognito access tokens issued to the CRM app client are accepted (ADR-0025).
- **Calls out:**
  - client-service `GET /api/clients/{clientId}` with the caller's token, over Service Connect, to check ownership and read the first name, country and email;
  - Cognito `ListUsers` filtered by `sub`, to find the agent's email address;
  - SES `SendEmail` (raw MIME);
  - Bedrock Runtime `Converse` in Singapore, with Amazon Nova Micro's Asia-Pacific cross-region profile (ADR-0054);
  - SQS `audit-insert`.
- **Dispatcher:**
  - runs inside every task every 60 seconds (ADR-0051 decision 4);
  - claims due rows with `FOR UPDATE SKIP LOCKED` and a 2-minute lease (`leased_until`), so two tasks never claim the same row;
  - sends outside the transaction, at most 1 message a second (SES sandbox rate), then records the result;
  - delivery is at least once: a task that dies after SES accepts a message leaves a lease that expires, and the row is sent again;
  - no advisory lock is needed now; it stays in reserve for a future single-reader channel such as Telegram;
  - outside sessions the stack is stopped (ADR-0042), so nothing is sent.
- **No change to dashboard-service.** The agent home page calls engagement-service directly and hides its panel if the service is unavailable.

## 7. Functional requirements

All routes are under `/api/engagement`. All need the `AGENT` group (§10).

| Endpoint | Purpose |
|---|---|
| `GET /events?from&to` | The caller's events in a window of at most 92 days |
| `POST /events` | Create. Body: title (≤ 120 chars), description (≤ 1,000), location, start, end, optional `clientId`, reminder offsets (0–2 of 1 day, 1 hour, 30 min, 15 min). Queues an invite |
| `GET /events/{id}` | One event |
| `PUT /events/{id}` | Update with optimistic locking (`version`). Queues an update with a higher `SEQUENCE` and reschedules reminders |
| `DELETE /events/{id}` | Cancel. Queues a cancellation and cancels pending reminders |
| `GET /holidays?country&from&to` | Public holidays, for the calendar view |
| `POST /drafts` | Generate. Body: `clientId`, `purpose` (`CHECK_IN`, `MEETING_FOLLOW_UP`, `HOLIDAY_GREETING`, `PRE_HOLIDAY_REVIEW`), `tone` (`FORMAL`, `WARM`), optional `eventId` |
| `GET /drafts?status` | The caller's drafts |
| `GET /drafts/{id}` | One draft, with placeholders filled in for display |
| `PUT /drafts/{id}` | Edit subject and body (optimistic locking) |
| `POST /drafts/{id}/send` | Send through SES. 409 if already sent or discarded |
| `DELETE /drafts/{id}` | Discard |
| `GET /preferences`, `PUT /preferences` | `invitesEnabled`, `remindersEnabled` (both default on) |

Times are stored in UTC and shown in Singapore time. Event times must be in the future when created and end after they start.

## 8. Relevant non-functional requirements

- **Performance:**
  - event API calls finish in under 1 second; mail is queued, never sent inside the request;
  - draft generation allows 15 seconds for the model, then falls back to the template (under API Gateway's 29-second limit);
  - sending allows 10 seconds for SES.
- **Availability:**
  - one task by default, and more can run safely (§6);
  - an SES or Bedrock failure never loses an event: mail stays queued and is retried up to 3 times with backoff, then marked `FAILED`.
- **Consistency:** event writes, mail-queue rows and audit outbox rows are written in one transaction.
- **Maintainability:** ports and adapters as in dashboard-service and event-alert-service, and an architecture test that keeps modules within their own tables.

## 9. APIs, schema and jobs

- **API contract:** `docs/api/engagement-api.yaml` (OpenAPI 3).
- **Schema:** `engagement-db`, Flyway migrations in an init container (ADR-0028, ADR-0042):

| Table | Key columns |
|---|---|
| `events` | `id`, `owner_agent_sub`, `client_id` (nullable), `title`, `description`, `location`, `starts_at`, `ends_at`, `status` (`SCHEDULED`, `CANCELLED`), `ics_uid`, `ics_sequence`, `version`, timestamps |
| `mail_queue` | `id`, `kind` (`INVITE`, `UPDATE`, `CANCEL`, `REMINDER`), `event_id`, `offset_minutes`, `due_at`, `status` (`PENDING`, `SENT`, `FAILED`, `MISSED`, `CANCELLED`), `attempts`, `next_attempt_at`, `leased_until`, `last_error_code`, `ses_message_id`, `sent_at`. Unique on (`event_id`, `kind`, `offset_minutes`, `ics_sequence`) |
| `email_drafts` | `id`, `owner_agent_sub`, `client_id`, `event_id`, `purpose`, `tone`, `subject_template`, `body_template`, `source` (`AI`, `TEMPLATE`), `model_id`, `holiday_id`, `status` (`DRAFT`, `SENT`, `DISCARDED`), `ses_message_id`, `sent_at`, `version`, timestamps |
| `holidays` | `id`, `country_code`, `holiday_date`, `name`, `source`, `dataset_version`. Unique on (`country_code`, `holiday_date`, `name`). Seeded by migration |
| `preferences` | `agent_sub`, `invites_enabled`, `reminders_enabled` |
| `ai_usage` | `usage_date`, `agent_sub`, `drafts`, `input_tokens`, `output_tokens` |
| `audit_outbox` | As event-alert-service |

- **Jobs:** the in-service dispatcher (§6). No scheduled ECS task or Lambda.

## 10. Authorization and access

- Every route requires the `AGENT` group. Admins get 403. Admin oversight is through the audit log (`GET /api/activities`, ADR-0021), and admins do not manage client contact (consistent with ADR-0050).
- The owner always comes from the token's `sub`. No route takes an agent ID. Another agent's event or draft returns 404.
- Linking a client, generating a draft and sending all check ownership through client-service with the caller's token, and fail closed: a client-service error refuses the request.
- A soft-deleted client (ADR-0036) cannot be linked or emailed. Existing events show "client unavailable".

## 11. Audit logging

`source_service` and `event_type` are both `ENGAGEMENT`. The payload is event-alert-service's envelope: `event_id`, `event_version`, `occurred_at` and a `data` object. Actions:

- `EVENT_CREATE`, `EVENT_UPDATE`, `EVENT_CANCEL`
- `REMINDER_SENT`, `REMINDER_FAILED`, `REMINDER_MISSED`
- `INVITE_SENT`, `INVITE_FAILED`
- `DRAFT_GENERATE` (with `source` AI or TEMPLATE), `DRAFT_DISCARD`
- `EMAIL_SEND`, `EMAIL_SEND_FAILED`
- `PREFERENCES_UPDATE`

- Payloads hold IDs, statuses and, for updates, changed field names with before and after times. They never hold titles, descriptions, email bodies or addresses.
- `actor_id` is the caller's `sub`, or `system` for dispatcher events.
- Written through the transactional outbox (ADR-0020) and relayed to `audit-insert`.
- The auditor gets the `ENGAGEMENT` type and payload checks (task AE-T08).
- The activity feed (`GET /api/activities`) shows `EVENT_CREATE`, `EVENT_UPDATE`, `EVENT_CANCEL` and `EMAIL_SEND`: the agent's own changes. Reminders, invites and drafts stay in the audit log only.

## 12. Error and failure behaviour

| Condition | Behaviour |
|---|---|
| Missing or invalid token | 401 |
| Admin caller | 403 |
| Client not owned, deleted or unknown | 404 for the client link; nothing saved |
| client-service unavailable | 503; nothing saved or sent (fail closed) |
| Stale `version` on update | 409 |
| SES error while dispatching | Retry up to 3 times with backoff, then `FAILED` and an audit event; the event itself is unaffected |
| Reminder due after its event started (for example, the stack was stopped) | `MISSED`, not sent |
| Agent email not found in Cognito | Mail `FAILED` with `agent_email_unavailable` |
| Bedrock disabled, over a cap, timed out, or output fails the safety screen | Template draft (`source = TEMPLATE`, with `sourceReason`), 201 |
| No holiday in the next 30 days for the client's country | Holiday purposes return 422; other purposes draft without a holiday |
| Send with unresolved placeholders or empty body | 422; not sent |
| SES error on send | 502; draft stays `DRAFT` |
| Second send | 409 |

## 13. Observability

- **Structured logs** (ADR-0027): no email addresses, names, titles or bodies; IDs only.
- **Metrics:**
  - mail sent, failed and missed, by kind;
  - dispatcher lag (oldest pending due item);
  - drafts by source (AI or template) and safety-screen rejection reasons;
  - Bedrock latency and tokens;
  - SES errors.
- **SES alerts:** an SES configuration set publishes bounces and complaints to CloudWatch. Alarm on bounce rate and on dispatcher lag over 5 minutes while running.

## 14. Cost impact

Rough figures; verify in the AWS Pricing Calculator for ap-southeast-1.

| Item | Running rate | Planned lifecycle (about 60 prod hours and one 48-hour staging test) |
|---|---|---|
| ECS Fargate, 1 task × 1 vCPU / 2 GB (as dashboard-service) | about USD 0.06 per hour | about USD 4–7 |
| RDS `db.t4g.micro`, single-AZ, 20 GB | about USD 0.02–0.03 per hour plus storage | about USD 3–6 |
| SES | USD 0.10 per 1,000 emails | under USD 1 |
| Bedrock, about 1,500 tokens per draft, capped | cents | under USD 1 |
| **Total** | | **about USD 10–15** |

- Current headroom is about USD 94 under the USD 300 limit (`docs/cost/budget.md`).
- **Levers:**
  - run the service only for test and demo slots;
  - put `engagement-db` on a shared x-factor RDS instance (budget.md fallback);
  - keep AI drafting off outside demo runs.
- No Multi-AZ for this database, even in the high-availability week.

## 15. Security and privacy

- **No personal data to the model.** The model receives:
  - the purpose and tone;
  - holiday names and dates (public data);
  - a banded "time since last meeting" (`< 1 month`, `1–3 months`, `> 3 months`, `none`);
  - allowed placeholders (`{{client_first_name}}`, `{{agent_name}}`).

  The service fills in placeholders after generation. The data rule mirrors ADR-0034. Unlike ADR-0034, the request may be processed in other Asia-Pacific regions through Nova Micro's geographic profile, never a global one (ADR-0054 decision 3).
- **Safety screen on model output:**
  - must be valid JSON `{subject, body}` within length limits (120 and 1,500 characters);
  - only allowed placeholders;
  - no URLs, digits followed by `%` or a currency, or blocked terms (rate, return, guarantee, invest, product names);
  - failure gives the template.
- **Human review is mandatory.** Drafts are never sent automatically.
- **Client email address:** read from client-service at send time with the caller's token, used once, and not stored. Client personal fields stay encrypted in client-service (ADR-0030).
- **Agent-typed titles and descriptions** may name a client. They go only to the agent's own mailbox and `engagement-db`, never to the model, and are never logged.
- **SES:**
  - sends from a subdomain identity of `itsag3t6.com` with DKIM;
  - `Reply-To` is the agent's address, so client replies reach the agent;
  - in sandbox mode only verified addresses receive mail, so demo clients use team inboxes;
  - synthetic data only.
- **Erasure:** `engagement-db` keeps client IDs and agent-typed text, which client crypto-shredding (ADR-0022, ADR-0030) does not reach. See open question Q6.
- **Secrets:** no new secrets beyond the database credentials. SES, Bedrock, Cognito and SQS use the task role.

## 16. Test plan

| Where | What |
|---|---|
| Unit | Reminder due times; rescheduling on update or cancel; iCalendar output (stable `UID`, `SEQUENCE` increases, UTC `DTSTART`/`DTEND`, `METHOD:REQUEST` and `METHOD:CANCEL`); holiday lookup; placeholder filling; safety screen; template fallback; caps and kill switch |
| Repository (Testcontainers Postgres) | Ownership filters; `SKIP LOCKED` claiming with two dispatchers; lease expiry; retries; unique queue rows; optimistic locking |
| Controller and security | Agent only; admin 403; no token 401; another agent's IDs 404; client-service 403, 404 or 503 handling |
| Adapters | SES with a mocked `SesV2Client`, the raw MIME parsed back; Bedrock gateway with a mocked client; client-service against real response JSON (served as `text/plain`, as client-service does); Cognito lookup with a mocked client |
| Model privacy | The captured Bedrock request has no client name, email, ID or agent-typed text |
| AI evaluation | Fixture set of purposes and holidays run through the safety screen, following `event-alert-service` `ai/evaluation` |
| Auditor | `ENGAGEMENT` events of each action pass; bad action or payload rejected |
| Frontend (Vitest) | Calendar page, event form validation, draft editor, send confirmation, panel hidden when the service is unavailable |
| End to end (AWS, sandbox) | Create, update and cancel an event: the invite, move and removal appear in a Gmail and an Outlook inbox. A reminder arrives at its offset. Draft, edit and send to a verified inbox. Audit rows appear in the auditor |

## 17. Deployment and migration

- **New stack:** `infrastructure/terraform/stacks/agent-engagement`, following ADR-0047. It reads CRM stack outputs through `terraform_remote_state` and owns:
  - the ECR repository;
  - the security group, plus a rule on client-service's group;
  - the ECS service (`modules/ecs-service`, Service Connect to client-service);
  - the database (`modules/postgres`);
  - the ALB rule for `/api/engagement` and `/api/engagement/*`, priority 70 (the next free band after event-alert-service's 60), to port 8086;
  - IAM: `ses:SendRawEmail` on the identity, `bedrock:InvokeModel` on the Nova Micro Asia-Pacific profile and its foundation-model ARNs in the profile's six regions (ADR-0054 Security), `cognito-idp:ListUsers` on the pool, `sqs:SendMessage` on `audit-insert` through `extra_audit_producer_role_arns`.
- **Long-lived stack `stacks/mail-identity`** (ADR-0053 decision 2), applied once per environment and never destroyed by the session scripts. It holds the SES domain identity (`mail.sg.itsag3t6.com` for prod), the DKIM, MAIL FROM and DMARC records in the existing hosted zone, the configuration set, and the verified team inboxes used as demo recipients. `stacks/agent-engagement` reads them by name, so teardowns never force re-verification.
- The CRM stack needs an output for this stack, like `dashboard_inputs`.
- **Order:** `mail-identity` once (then confirm DKIM and click each inbox's verification email), then per session the CRM stack and then this stack. Add steps to `docs/runbooks/deploy-aws.md`.
- **Configuration** (names only; described in the service README). Names follow the existing services, so `CLIENT_SERVICE_BASE_URL` rather than `CLIENT_SERVICE_URL`, and the issuer is `COGNITO_ISSUER_URI`:
  - `ENGAGEMENT_DB_URL`, `ENGAGEMENT_DB_USERNAME`, `ENGAGEMENT_DB_PASSWORD`, `SPRING_FLYWAY_ENABLED`
  - `COGNITO_ISSUER_URI`, `COGNITO_APP_CLIENT_ID`, `COGNITO_USER_POOL_ID`
  - `CLIENT_SERVICE_BASE_URL`
  - `ENGAGEMENT_AUDIT_PUBLISHER_ENABLED`, `AUDIT_QUEUE_URL`, `AWS_REGION`
  - `ENGAGEMENT_MAIL_PROVIDER` (`none` by default, `ses` in AWS), `SES_FROM_ADDRESS`, `SES_CONFIGURATION_SET`, `ENGAGEMENT_MAIL_DOMAIN`
  - `CRM_APP_URL`
  - `ENGAGEMENT_AI_ENABLED` (default `false`)
  - `BEDROCK_REGION`, `BEDROCK_MODEL_ID`
  - `ENGAGEMENT_AI_DAILY_DRAFTS_PER_AGENT`, `ENGAGEMENT_AI_MONTHLY_TOKEN_QUOTA`
- **Port:** 8086 locally and in AWS.
- **Local:** Docker Compose with Postgres and LocalStack (SQS); mail goes to the log (`ENGAGEMENT_MAIL_PROVIDER=log`).
- **Migrations:** the db-migrate image copies `services/engagement-service/src/main/resources/db/migration/` to `/flyway/sql/engagement/`, with the shared app-login script.

## 18. Rollback

Scale the service to zero or destroy the `agent-engagement` stack. No other service calls it. The agent home page hides the "Upcoming meetings" panel. Invites already delivered stay in agents' calendars, which is acceptable for synthetic demo data. The SES identity can stay.

## 19. ADRs required before implementation

All drafted and accepted 2026-10-21, owner Neo Jia Wen, and indexed under Accepted decisions in `docs/adr/README.md`. ADR-0054 records the Nova Micro profile ID `apac.amazon.nova-micro-v1:0` and its six destination regions.

| ADR | Decision |
|---|---|
| ADR-0051 | engagement-service as a new microservice with its own database (extends ADR-0017 and ADR-0023). One deployable with internal modules. An in-process dispatcher in every task that claims rows with `SKIP LOCKED` and a lease. Mail only while the stack runs (ADR-0042 consequence) |
| ADR-0052 | Calendar integration through emailed iCalendar invites: one-way, agent as the only attendee, stable `UID`, `SEQUENCE` on update, `METHOD:CANCEL`. No provider APIs or OAuth |
| ADR-0053 | Email through Amazon SES: subdomain identity with DKIM, sandbox for the demo, `Reply-To` the agent, agent address from Cognito by `sub`, client address from client-service at send time, no resolved bodies or addresses stored |
| ADR-0054 | AI follow-up drafts: Bedrock model and region, the placeholder privacy boundary, no free text to the model, the safety screen, template fallback, mandatory human review, caps and kill switch, and the static versioned holiday table as the data source |

dashboard-service, ADR-0033 and ADR-0038 are unchanged.

## 20. Task breakdown

Joseph Yau reviews anything touching Feature 2 contracts. The auditor owner reviews AE-T08. The frontend owner reviews AE-T09.

| Task ID | Task | Primary dev | Collaborators | Files/components | Branch | Dependencies/conflict zones | Status | Last sync |
|---|---|---|---|---|---|---|---|---|
| AE-T01 | This plan, ADR-0051 to 0054, `engagement-api.yaml` | Neo Jia Wen | — | `docs/feature-plans/agent-engagement_plan.md`, `docs/adr/`, `docs/api/engagement-api.yaml` | `task/agent-engagement/plan` | ADR index (`docs/adr/README.md`) | Done: ADRs accepted 2026-10-21; contract drafted | 2026-10-21 |
| AE-T02 | Service skeleton: security, config, Flyway V1, Dockerfile, Compose | Neo Jia Wen | — | `services/engagement-service/**`, root `pom.xml`, `infrastructure/db-migrate/` (engagement migrations) | `task/agent-engagement/service` | Root `pom.xml` modules; db-migrate image (shared) | Built; tests pass | 2026-10-21 |
| AE-T03 | Events API and client-ownership check | Neo Jia Wen | Joseph Yau (review) | `engagement-service` `calendar` module | `task/agent-engagement/service` | client-service `GET /api/clients/{id}` contract (read-only use) | Built; tests pass | 2026-10-21 |
| AE-T04 | Mail queue, dispatcher, SES adapter, iCalendar, reminders | Neo Jia Wen | — | `notifications`, `reminders` modules | `task/agent-engagement/service` | AE-T03 | Built; tests pass | 2026-10-21 |
| AE-T05 | Holiday table, seed and API | Neo Jia Wen | — | `holidays` module, Flyway V2 | `task/agent-engagement/service` | — | Built; dates checked against MOM (page updated 2026-06-19) | 2026-10-21 |
| AE-T06 | AI drafting: gateway, safety screen, templates, caps, kill switch, evaluation fixtures | Neo Jia Wen | — | `drafting` module | `task/agent-engagement/service` | AE-T05; ADR-0054 accepted | Built; tests pass; live Nova run in AE-T11 | 2026-10-21 |
| AE-T07 | Draft edit, send and discard through SES | Neo Jia Wen | — | `drafting`, `notifications` modules | `task/agent-engagement/service` | AE-T04, AE-T06 | Built; tests pass | 2026-10-21 |
| AE-T08a | Audit outbox and relay | Neo Jia Wen | — | `audit` module | `task/agent-engagement/service` | — | Built; tests pass | 2026-10-21 |
| AE-T08b | Auditor `ENGAGEMENT` type, payload checks and activity-feed actions | Neo Jia Wen | Auditor owner (review) | `services/auditor/src/internal/models/service_type.go`, `activity_filter.go`, `src/serverd/queue/payload_unmarshalling.go`, their tests, `services/auditor/README.md` | `task/agent-engagement/auditor` | Auditor validation (shared) | Built; tests pass | 2026-10-21 |
| AE-T09 | Frontend: calendar, event form, draft editor, home panel, preferences | Neo Jia Wen | Frontend owner (review) | `frontend/src/pages/AgentCalendar.jsx`, `AgentDrafts.jsx`, `AgentDashboard.jsx`, `frontend/src/components/engagement/`, `frontend/src/api/engagement.js`, `AppRouter.jsx`, `NavBar.jsx`, `components/activityText.js`, `vite.config.js` | `task/agent-engagement/frontend` | `AgentDashboard.jsx` and `AppRouter.jsx` are shared with BD-T12 | In progress | 2026-10-21 |
| AE-T10 | Terraform stacks, SES identity, runbook | Neo Jia Wen | Platform | `infrastructure/terraform/stacks/agent-engagement/**`, `infrastructure/terraform/stacks/mail-identity/**`, CRM stack outputs (`engagement_inputs`), `scripts/aws-session.sh`, `docs/runbooks/deploy-aws.md`, `docs/cost/resource-register.md` | `task/agent-engagement/infrastructure` | CRM stack outputs (shared with ADR-0047 work) | In progress | 2026-10-21 |
| AE-T11 | End-to-end checks, seed data, cost check, live Nova evaluation | Neo Jia Wen | — | all of the above | `task/agent-engagement/e2e` | All four branches merged; verified SES inboxes; Cognito agent users | Planned | 2026-10-21 |

Branches. The work is split by area, one task branch each, all from `feat/agent-engagement`, built in separate git worktrees. Each branch touches only its own files, so they merge without conflicts:

| Branch | Owns |
|---|---|
| `task/agent-engagement/service` | `services/engagement-service/**`, root `pom.xml`, the db-migrate image's engagement lines, and the shared docs (this plan, the AI usage log) |
| `task/agent-engagement/auditor` | The auditor files of AE-T08b |
| `task/agent-engagement/frontend` | `frontend/**` |
| `task/agent-engagement/infrastructure` | The Terraform, script, runbook and cost files of AE-T10 |

Merge path: `task/agent-engagement/<area>` → `feat/agent-engagement` → `dev`.

## 21. Agent skills needed

- Existing project skills: none (`SKILLS.md` does not exist on `dev`).
- Skills to search for: Spring Boot testing with Testcontainers, iCalendar (RFC 5545) generation, SES deliverability, Bedrock prompt safety.
- Skills evaluated: none yet.
- Skills approved for this feature: none.

## 22. Open questions

| # | Question | Owner | Blocks |
|---|---|---|---|
| Q1 | Does the team accept a fourth innovation feature, its cost (§14) and a demo slot? **Resolved 2026-10-21:** the team accepted it | Team | — |
| Q2 | Which Bedrock model and region? **Resolved 2026-10-21:** Amazon Nova Micro through its Asia-Pacific geographic profile, called through Bedrock Runtime in Singapore (ADR-0054 decision 3). No small, current model runs in-region there. Profile ID `apac.amazon.nova-micro-v1:0` confirmed. Its destination regions (Singapore, Sydney, Tokyo, Seoul, Osaka, Mumbai) are recorded in ADR-0054; re-check them before the demo | Neo Jia Wen | — |
| Q3 | Agent email from Cognito `ListUsers` by `sub` (no user-service change), or a new user-service `GET /api/users/me`? **Resolved 2026-10-21:** Cognito `ListUsers` by `sub`, as ADR-0053 decision 4 (accepted) | Neo Jia Wen | — |
| Q4 | Should admins get read-only access to agents' calendars, or is the audit log enough? | Team | §10 |
| Q5 | Which countries beyond Singapore need holiday data, given the client `country` values in the synthetic data? | Neo Jia Wen | AE-T05 |
| Q6 | On client deletion or erasure, should engagement-service drop the client link and drafts? That needs a deletion signal from client-service | Neo Jia Wen, Joseph Yau | Erasure story |
| Q7 | Can a health-monitor task offer a "Draft email" link with a neutral purpose only, never its signals? | Garvit, Swayam | Later integration |
| Q8 | Is SES sandbox enough for the demo, or is production access needed? | Neo Jia Wen | AE-T11 |
| Q9 | Telegram reminders: when, if ever? Needs its own ADR (bot token, chat linking, content limits) | Neo Jia Wen | — |
