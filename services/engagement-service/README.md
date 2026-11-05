# engagement-service

Agents' calendar events, the calendar invites and reminder emails they cause, and AI-drafted client follow-up emails that the agent reviews and sends, including follow-ups from a Client Health task or an alert.

Feature plan: [`docs/feature-plans/agent-engagement_plan.md`](../../docs/feature-plans/agent-engagement_plan.md). API: [`docs/api/engagement-api.yaml`](../../docs/api/engagement-api.yaml). Decisions: [ADR-0051](../../docs/adr/ADR-0051-engagement-service-and-mail-dispatcher.md) (service, database, dispatcher), [ADR-0052](../../docs/adr/ADR-0052-calendar-invites-by-email.md) (invites), [ADR-0053](../../docs/adr/ADR-0053-email-delivery-through-ses.md) (SES), [ADR-0054](../../docs/adr/ADR-0054-ai-follow-up-drafts-and-holiday-data.md) (AI drafts, holidays), [ADR-0068](../../docs/adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md) (follow-ups from Client Health and alerts, draft text retention).

## How it works

**Agents only.** Every route under `/api/engagement` needs the `AGENT` group; admins get 403 and oversee client contact through the audit log. The owner of every event, draft and preference is the token's `sub`. No route takes an agent ID, and another agent's records return 404.

| Module | Owns | Does |
|---|---|---|
| `calendar` | `events` | Events, the client-ownership check, iCalendar invites, the messages the dispatcher sends |
| `reminders` | (none) | Reminder due times, rescheduling when an event changes |
| `notifications` | `mail_queue`, `preferences` | The mail queue, the dispatcher, the SES channel, notification preferences |
| `drafting` | `email_drafts`, `ai_usage` | Bedrock drafts, the safety screen, templates, caps, sending to the client, expiring open drafts |
| `holidays` | `holidays` | The public holiday table, seeded by Flyway and added to by the data.gov.sg sync |
| `audit` | `audit_outbox` | The transactional outbox and its relay to the shared audit queue |

A module reads and writes only its own tables; `ArchitectureTest` enforces it.

- **Events and invites.** Creating, updating or cancelling an event writes the event, a queued invite, update or cancellation, its reminders and an audit event in one transaction. Mail is never sent inside the request. The invite is an iCalendar `REQUEST` or `CANCEL` with a stable `UID` (`<event id>@<mail domain>`) and a rising `SEQUENCE`, so Gmail and Outlook add, move and remove the entry. Changes the agent makes in their own calendar are not read back.
- **Client check.** Linking a client, drafting and sending ask client-service `GET /api/clients/{id}` with the agent's own token. 401, 403 and 404 mean "not yours" (404); any other failure saves and sends nothing (503). client-service writes JSON without a JSON content type, so the body is parsed as text.
- **Dispatcher.** Every task runs it every 60 seconds. It claims up to 25 due rows with `FOR UPDATE SKIP LOCKED` and a 2-minute lease, sends outside the transaction at most once a second, then records each result. A failed send is retried after 1, 5 and 15 minutes, then `FAILED`. A reminder whose meeting has started is `MISSED`. Delivery is at least once: a task that dies after SES accepts a message leaves a lease that expires. While the stack is stopped nothing is sent. When due mail has waited over 5 minutes it logs `mail_dispatch_lagging`, which the stack alarms on.
- **Agent address.** Cognito `ListUsers` filtered by `sub`, cached 15 minutes. An agent who cannot be found gets no mail (`agent_email_unavailable`).
- **Drafts.** The model gets the purpose, tone, `en-SG`, up to 3 public holidays in the next 30 days, a banded time since the last meeting and the two placeholders, nothing else. Its JSON reply must pass the safety screen. With AI off, a cap reached, a timeout, an error or a rejected reply, a fixed template is used and the draft says why (`sourceReason`). Drafts are stored with `{{client_first_name}}` and `{{agent_name}}`. Responses carry `subject` and `body` with the names filled in, and `PUT` takes plain text and marks the names as placeholders again before storing it, so no names are stored ([ADR-0056](../../docs/adr/ADR-0056-drafts-edited-as-plain-text.md)).
- **Follow-ups from another feature.** `HEALTH_CHECK_IN` needs `healthTaskId`, and `ALERT_FOLLOW_UP` needs `alertId` (ADR-0068).
  - The task or alert is read through the internal ALB with the agent's own token (`integration/sources`). 401, 403 and 404 mean "not yours" (400). It must be about the draft's client: the task's `clientId`, or the alert's `clientRef`, which is the CRM client ID for CRM-linked holdings. Any other failure saves nothing (503).
  - Only that client field is parsed. Nothing from the task or alert reaches the model.
  - Their text has one `{{agent_details}}` slot, shown as `[Add your details here]`. The screen requires it for these purposes and refuses it elsewhere. Sending is refused while it is untouched.
- **Sending a draft.** Only the agent's explicit `POST /drafts/{id}/send`, with the version they last saw. The draft is claimed first, so it is sent at most once. The client's address is read at send time and never stored; `Reply-To` is the agent, and the agent gets a Bcc copy, named only in SES's destination. What the agent added is sent as written; it is theirs to check. Only the SES message ID is kept: the subject and body are cleared once SES accepts the message, and `EMAIL_SEND` records whether the agent `edited` the generated text.
- **Draft text retention.** Discarding clears the text too. Every task runs a daily job, and one soon after start-up, that discards open drafts not changed for 14 days (`FOR UPDATE SKIP LOCKED`), audited as `DRAFT_DISCARD` by `system` with `reason: "expired"`.
- **Holidays.** Singapore 2026 and 2027 from the Ministry of Manpower, in `V2__seed_singapore_holidays.sql`. With `ENGAGEMENT_HOLIDAY_SYNC_ENABLED`, a sync adds each newly published year from MOM's data.gov.sg dataset a minute after start-up and daily, adding rows only ([ADR-0057](../../docs/adr/ADR-0057-holiday-sync-from-data-gov-sg.md)); `GET /holidays/coverage` says how far the list reaches. Corrections go in new migrations. Only countries mapped in `CountryCodes` get holidays.

## Running it locally

From the repository root:

```sh
docker compose -f services/engagement-service/compose.yaml up -d

COGNITO_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id> \
COGNITO_APP_CLIENT_ID=<crm-app-client-id> \
ENGAGEMENT_DB_PASSWORD=engagement \
CLIENT_SERVICE_BASE_URL=http://localhost:8080 \
ENGAGEMENT_MAIL_PROVIDER=log \
  mvn -pl services/engagement-service spring-boot:run
```

- `http://localhost:8086/actuator/health` returns `{"status":"UP"}`, unauthenticated.
- `/api/engagement/*` needs a CRM access token; the frontend's dev proxy sends `/api/engagement` here.
- With `ENGAGEMENT_MAIL_PROVIDER=log`, the dispatcher logs a message ID instead of sending. Without `COGNITO_USER_POOL_ID`, no agent can be looked up, so queued mail fails with `agent_email_unavailable`.
- Flyway applies the migrations at startup. In AWS the db-migrate init container applies them instead (`SPRING_FLYWAY_ENABLED=false`).

## Configuration

| Variable | Purpose |
|---|---|
| `ENGAGEMENT_DB_URL`, `ENGAGEMENT_DB_USERNAME`, `ENGAGEMENT_DB_PASSWORD` | engagement-db. Default `jdbc:postgresql://localhost:5436/engagement` |
| `COGNITO_ISSUER_URI` | Cognito user pool issuer. Required |
| `COGNITO_APP_CLIENT_ID` | The CRM app client. Only access tokens issued to it are accepted. Required |
| `COGNITO_USER_POOL_ID` | For agents' addresses and names. Blank: no mail reaches agents |
| `CLIENT_SERVICE_BASE_URL` | client-service. Blank: every client check fails closed with 503 |
| `CLIENT_HEALTH_BASE_URL`, `ALERT_SERVICE_BASE_URL` | The internal ALB, for Client Health tasks and alerts (ADR-0068). Set only with a custom domain. Blank: `HEALTH_CHECK_IN` and `ALERT_FOLLOW_UP` fail closed with 503 |
| `ENGAGEMENT_MAIL_PROVIDER` | `none` (default: nothing is sent, the dispatcher does nothing), `log` (local) or `ses` |
| `SES_FROM_ADDRESS` | The `no-reply` sending address, or the verified `sender_address` in an account without the domain ([ADR-0055](../../docs/adr/ADR-0055-sender-address-without-a-domain.md)) |
| `SES_CONFIGURATION_SET` | The SES configuration set |
| `ENGAGEMENT_MAIL_DOMAIN` | The sending domain (the sender address's domain without a custom domain), also the calendar `UID` suffix |
| `CRM_APP_URL` | The CRM's address, linked from invites and reminders |
| `ENGAGEMENT_AI_ENABLED` | The kill switch. Default `false`: every draft is a template |
| `ENGAGEMENT_ALERT_REPORTS_ENABLED` | The rollout kill switch for emailing event reports to clients (ADR-0084). Default `true`. Set `false` and `POST /api/engagement/alert-report-deliveries` answers 503 and nothing is queued or sent |
| `ENGAGEMENT_HOLIDAY_SYNC_ENABLED` | Add newly published Singapore holidays from data.gov.sg (ADR-0057). Default `false`; `true` in AWS |
| `BEDROCK_REGION`, `BEDROCK_MODEL_ID` | Default `ap-southeast-1` and `apac.amazon.nova-lite-v1:0` (ADR-0067). A `global.` profile is refused at startup |
| `ENGAGEMENT_AI_DAILY_DRAFTS_PER_AGENT`, `ENGAGEMENT_AI_MONTHLY_TOKEN_QUOTA` | Caps. Default 20 and 300,000 |
| `ENGAGEMENT_AUDIT_PUBLISHER_ENABLED`, `AUDIT_QUEUE_URL` | The relay to the shared audit queue. Off by default |
| `AWS_REGION`, `AWS_ENDPOINT_URL` | For SQS, SES and Cognito. The endpoint override is for LocalStack only |
| `SPRING_FLYWAY_ENABLED` | `false` in AWS, where db-migrate runs the migrations |

Tunables in `application.yml` (`engagement.mail.dispatcher.*`, `engagement.ai.*`, `engagement.drafts.*`) are not environment variables on purpose.

## Port

8086. The others: client-profile-management-service 8080, user-service 8081, receiver API 8082, account-service 8083, dashboard-service 8085.

## Tests

```sh
mvn -B verify -pl services/engagement-service -am
```

Docker must be running: the integration tests use one Testcontainers PostgreSQL for every test class, and Flyway applies the real migrations. client-service and Cognito are mocked; SES, Bedrock and Cognito clients are mocked in their adapter tests. Notable tests:

- `MailDispatcherTest`: two dispatchers at once send each invite once; retries; leases that expire; missed reminders.
- `DraftApiTest`: the captured model input holds no client name, address or ID, and nothing the agent typed; unsafe output falls back to the template; send once, 409 on a second send, 502 on an SES failure.
- `DraftScreenEvaluationTest`: the hand-written cases in `src/test/resources/ai-evaluation/`, including the details slot.
- `DraftExpiryTest`: open drafts expire after 14 days, in batches; sending drafts are left alone.
- `HttpFollowUpSourcesTest`: Client Health tasks and alerts are read with the agent's token, and only their client.
- `ArchitectureTest`: modules keep to their own tables.

## Security

- No secrets beyond the database credentials; SES, Bedrock, Cognito and SQS use the task role.
- Logs and audit events hold IDs only: never titles, descriptions, email bodies or addresses.
- The model never sees who the client is (ADR-0054 decision 2), nor anything from a Client Health task or an alert (ADR-0068), and a person approves every email.
- A draft's text is kept only while it is open (ADR-0068 decision 4).
