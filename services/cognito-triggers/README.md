# Cognito triggers

One Lambda function that the Cognito user pool runs after a login and after a confirmation. It sends Feature 1's login and self-service password-reset events to the shared `audit-insert` queue, and **fails open**: if sending fails, the user still signs in, and the event is logged for replay ([ADR-0020](../../docs/adr/ADR-0020-audit-write-path-outbox-shared-sqs.md) decision 4, [ADR-0025](../../docs/adr/ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) decision 5). It is plain Java 25 with no framework and no SnapStart, given enough memory to start well inside Cognito's 5-second limit ([ADR-0040](../../docs/adr/ADR-0040-cognito-triggers-java-lambda.md)).

| Trigger source | Audited as |
|---|---|
| `PostAuthentication_Authentication` | `LOGIN` |
| `PostConfirmation_ConfirmForgotPassword` | `PASSWORD_RESET` (shown in the activity feed) |
| anything else, such as `PostConfirmation_ConfirmSignUp` | not audited; the event is returned unchanged |

Logout, user changes and admin password resets are audited by user-service, not here.

## What it sends

The auditor's usual envelope (`AuditMessage`), with a new `event_id` for each call:

```json
{
  "event_id": "1f0e5c2a-...",
  "event_type": "AUTHENTICATION",
  "action": "LOGIN",
  "source_service": "COGNITO_TRIGGERS",
  "actor_id": "<the user's Cognito sub>",
  "payload": {
    "user_pool_id": "ap-southeast-1_Example01",
    "trigger_source": "PostAuthentication_Authentication",
    "datetime": "2026-10-13T05:54:00.123Z",
    "app_client_id": "example0app0client0id0001"
  }
}
```

`app_client_id` is left out when Cognito names no app client. The user's attributes (email, phone number, name) are never copied into the message or a log line. The auditor checks this format (`services/auditor`, `validateAuthenticationPayload`), and its test uses the same text as `AuditMessageTest`.

## How it stays inside 5 seconds

Cognito waits 5 seconds for a trigger, cold start included, and the wait cannot be changed. The handler gives itself 3 seconds from the moment it is called (`Handler.SEND_BUDGET`), or less if Lambda's own timeout is closer.

- Each SQS attempt is cut off by a 0.5-second connect timeout and a 1-second read timeout. The SDK's own retries are off (`SqsQueueSender`).
- After a failed attempt it waits 50 ms, doubling each time, and tries again only if another whole attempt would still finish within the budget (`SendBudget`).
- The SDK is loaded by the first send, not when Lambda creates the handler, so even a broken package cannot stop a login.

Whatever goes wrong (no queue URL, the SDK fails to load, an unreadable event, every attempt failing), the handler writes one JSON line and returns the Cognito event:

```json
{"level":"ERROR","msg":"AUDIT_SEND_FAILED","audit_event":{...},"trigger_source":"...","user_pool_id":"...","error":"SdkClientException: ..."}
```

`audit_event` is the full message to replay, or `null` if the Cognito event could not be read. A successful send logs `AUDIT_SENT` with the `event_id` and the number of attempts.

### Measured cold starts

Measured locally on 2026-10-13 with `target/cognito-triggers.zip`, unpacked read-only as Lambda does, on Corretto 25.0.4 (the JDK of Lambda's `java25` runtime). JIT compilation stopped at level 1, which is Lambda's default for Java 25 without SnapStart. Docker CPU limits stood in for the CPU share Lambda gives each memory size, which is a full vCPU at 1,769 MB. Each figure is from the JVM starting to the end of one call: loading the classes, creating the SQS client and one send to LocalStack.

| Memory | CPU share | Cold start |
|---|---|---|
| 512 MB | 0.29 vCPU | 1.9 s |
| 1,024 MB | 0.58 vCPU | 0.9 s |
| **1,769 MB (chosen)** | 1 vCPU | **0.5 s** |

At 256 MB the same work took about 4 s. A warm call takes about 6 ms. At 1,769 MB a cold start leaves about 2.5 seconds of the budget for retries. These are estimates: Lambda's own runtime start-up is not included, so check the `Init Duration` and `Duration` of a cold start in the function's `REPORT` log lines once it is deployed.

## Building and testing

It is a module of the root Maven build, so CI's `mvn -B verify` builds and tests it with the other Java services.

| Command | What it does |
|---|---|
| `make test` | Every test, then `target/cognito-triggers.zip`. Needs JDK 25, Maven and Docker. |
| `make package` | The zip only, without the tests. |
| `make invoke` | Runs the zip's handler once with `src/test/resources/events/post-authentication.json` against the auditor's local queue. `make invoke EVENT=confirm-forgot-password` sends a password reset. Needs Docker only. |
| `make replay` | Replays logged failures (below). |
| `make clean` | Removes `target/`. |

On Windows, Testcontainers needs the Docker API version pinned; the root `pom.xml` does this in its `windows-docker-api-version` profile.

The tests:
- `AuditMessageTest`: the message for each trigger source, matching the auditor's test text; no user attribute in it.
- `SendBudgetTest`: the retry and budget rules, with a fake clock.
- `HandlerTest`: every failure path returns the event and logs `AUDIT_SEND_FAILED`.
- `RealSdkTest`: the real SDK against a port that refuses connections and a server that never answers, both finishing within the budget.
- `LocalStackTest`: a login arrives on a LocalStack queue as the auditor reads it, and a failed send replays with the same `event_id` (Testcontainers).
- `ReplayTest`: the replay tool.

### Trying it with the auditor

Start the auditor and its LocalStack queue (`make run` in `services/auditor`), then:

```sh
make package
make invoke
make invoke EVENT=confirm-forgot-password
```

The auditor stores both events in its chain (`make psql` in `services/auditor`, then `SELECT sequence_no, source_service, action, actor_id FROM audit_log;`). `src/test/resources/events/` holds the synthetic sample events; `confirm-sign-up.json` is one the function ignores.

## Configuration

| Variable | Purpose |
|---|---|
| `AUDIT_QUEUE_URL` | Required. URL of the `audit-insert` queue. Without it every event is logged as `AUDIT_SEND_FAILED`. |
| `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` | Set by Lambda: the region and the function role's credentials. The client reads them directly rather than searching the SDK's default chains. |
| `AWS_ENDPOINT_URL` | Local runs only (LocalStack). Leave unset in AWS. |

## Replaying failed events

When the `AUDIT_SEND_FAILED` alarm fires, fix the cause first (for example the queue policy, or an SQS outage). Then, with the replay role's credentials in the environment (ADR-0027):

```sh
aws logs filter-log-events --log-group-name /aws/lambda/cognito-triggers \
  --start-time <milliseconds since 1970, before the first failure> \
  --filter-pattern '{ $.msg = "AUDIT_SEND_FAILED" }' > failed.json

make package
make replay QUEUE_URL=<audit-insert URL> LOG_FILE=failed.json
```

Each event is sent exactly as logged, with its `event_id`, so replaying an event twice, or one that reached the auditor after all, stores nothing new. The tool (`Replay`) prints each `event_id` it sent. It exits 1 if any could not be sent, or if a failure line holds no audit event. Such a line means the trigger could not read the Cognito event. Its `trigger_source`, `user_pool_id` and time show which sign-in it was, and it must be looked into by hand.

## Infrastructure requirements

Nothing here is built yet. Terraform will need ([ADR-0040](../../docs/adr/ADR-0040-cognito-triggers-java-lambda.md)):
- **The function:**
  - runtime `java25` on `arm64`, handler `bank.cognito.Handler::handleRequest`, code `target/cognito-triggers.zip`;
  - 1,769 MB of memory and a 5-second timeout;
  - `AUDIT_QUEUE_URL` set;
  - no SnapStart and no provisioned concurrency;
  - not attached to the VPC: it reaches SQS through the public regional endpoint over TLS, so it needs no NAT, network interface or VPC endpoint.
- **The user pool:** its `PostAuthentication` and `PostConfirmation` triggers point at the function, and `lambda:InvokeFunction` is granted to `cognito-idp.amazonaws.com` for that user pool only.
- **The function's IAM role:**
  - `sqs:SendMessage` on `audit-insert` only;
  - `kms:GenerateDataKey` and `kms:Decrypt` on the queue's key, if the queue uses a customer-managed key;
  - writing to its own log group.
- **The queue policy:** the role is listed as a producer.
- **Logs and alarms (ADR-0027):**
  - the `/aws/lambda/cognito-triggers` log group, with the project's retention;
  - a metric filter on `{ $.msg = "AUDIT_SEND_FAILED" }`, alarmed at 1 or more to the Feature 1 owner;
  - an alarm on the function's `Errors` metric, which should always be 0: an error means Cognito refused that sign-in;
  - an alarm on its maximum `Duration` above 4 seconds, which is close to Cognito's limit.
- **The replay role:** `logs:FilterLogEvents` on the log group and `sqs:SendMessage` on `audit-insert`.

## Dependencies

Versions come from the root `pom.xml`: the AWS SDK BOM, and Spring Boot's BOM for Jackson, SLF4J, Lombok and the test libraries. Testcontainers matches user-service. The SQS client is used without its default Netty and Apache HTTP clients, which the function does not need; the JDK's URL connection client takes their place. `slf4j-nop` silences the SDK's logging, which would otherwise warn on every cold start. Lombok is compile-time only (`provided`), so it is not in the zip.
