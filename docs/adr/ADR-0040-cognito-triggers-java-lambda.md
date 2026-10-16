# ADR-0040: Cognito triggers as one plain Java 25 Lambda at 1,769 MB, sending within a fixed time budget

- Status: Accepted
- Decision date/time: 2026-10-13 14:55 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen (Feature 1 owner, who maintains the triggers)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/governance/adr-0040-trigger-runtime` (this record); `task/base-features/cognito-triggers` (implementation)
- Related issue/PR: TBD (pull requests from both branches to `dev`)
- Supersedes: N/A. The runtime is the Java 25 baseline ([ADR-0017](ADR-0017-microservices-per-business-area.md), `project_plan.md` §2.2), so no runtime exception is needed.
- Superseded by: N/A

## Context

[ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md) decision 4 and [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) decision 5 audit logins and user-initiated password resets with two Cognito Lambda triggers, `PostAuthentication` and `PostConfirmation_ConfirmForgotPassword`. They send each event straight to `audit-insert` and fail open: if the send fails, the event is logged for replay and the sign-in still succeeds. Nothing implements them yet, so Feature 1's login and password-change auditing is missing.

Cognito calls a trigger synchronously and waits 5 seconds, which cannot be changed. If the trigger times out, Cognito retries it, and after three failed attempts it returns an error. An error from the trigger is also returned to the user instead of tokens. The 5 seconds include the function's cold start.

Java 25 is the project's default runtime. A plain JVM is often said to start too slowly on Lambda for a limit like this. The Feature 1 owner, who maintains the triggers, works in Java and Python but not Go. The cold starts were therefore measured rather than assumed (Validation).

## Decision Drivers

- Fit inside Cognito's 5 seconds, cold start included, with time left to retry.
- A login must never fail because auditing failed (ADR-0020 decision 4).
- Stay on the Java 25 baseline unless it cannot meet the limit.
- The Feature 1 owner can maintain and explain the code.
- Few moving parts, and almost no cost.
- No personal data in audit events or logs.

## Options Considered

### Option A: plain Java 25 at a small memory size (256–512 MB)

- Pros: the baseline runtime, and the cheapest per call.
- Cons: Lambda's CPU share grows with memory, and a cold start measured about 4 seconds at 256 MB and 1.9 seconds at 512 MB. That leaves little or no time to retry within Cognito's 5 seconds.

### Option B: plain Java 25 at 1,769 MB (chosen)

- Pros:
  - the baseline runtime, with no exception to justify and no new language or CI toolchain;
  - a full vCPU, and a cold start of about 0.5 seconds (Validation);
  - no SnapStart, so no alias, keep-warm ping or snapshot to manage;
  - the same SDK as user-service, in a language the owner knows.
- Cons: far more memory than the work needs, bought only for CPU. Lambda's own runtime start-up could not be measured locally.

### Option C: Java 25 with SnapStart

- Pros: Lambda supports SnapStart on Java 25 at no extra cost, and a restore usually takes a few hundred milliseconds.
- Cons:
  - SnapStart works only on published versions, so Cognito must call an alias.
  - A version not called for 14 days loses its snapshot, and the next call fails with `SnapStartNotReadyException`, most likely failing that sign-in, unless a scheduled ping keeps it active. Environments in this project sit unused between demos ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)).
  - Its start time cannot be checked until the infrastructure exists.
  - Unnecessary once Option B fits.

### Option D: Python 3.14

- Pros: about 1.6 times faster to start than Java at every memory size measured (0.3 seconds at 1,769 MB).
- Cons: a third deployed language, needing a runtime ADR, its own CI job, linting and dependency updates. The 0.2 seconds it saves at 1,769 MB do not matter against a 5-second limit. It was built first, then replaced before merging once Java was measured.

### Option E: Go on Lambda's OS-only runtime

- Pros: likely the fastest start, and the same shape as transaction-ingest ([ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 7).
- Cons: the owner does not know Go.

### Option F: provisioned concurrency, with any runtime

- Pros: no cold starts.
- Cons: billed all the time, about USD 5 or more a month per function, for a function called a few hundred times a day.

## Decision

1. **Runtime:** Lambda's managed `java25` runtime on arm64, as a plain JVM with no framework, no SnapStart and no provisioned concurrency. Lambda's default JIT setting for Java 25 (tiered compilation stopping at level 1) is kept. The function has 1,769 MB of memory, for a full vCPU, and a 5-second timeout as a backstop.
2. **One function for both triggers**, `services/cognito-triggers`, set as the user pool's `PostAuthentication` and `PostConfirmation` trigger. It audits:
   - `PostAuthentication_Authentication` as `LOGIN`;
   - `PostConfirmation_ConfirmForgotPassword` as `PASSWORD_RESET`.

   Any other trigger source, such as `PostConfirmation_ConfirmSignUp`, is returned unchanged and sends nothing.
3. **The message** uses the auditor's envelope:
   - `source_service` is `COGNITO_TRIGGERS`. It is a name of its own, so that sender verification ([open item 18](../architecture/open-items.md#18-audit-sender-verification)) can tie it to the function's IAM role.
   - `event_type` is `AUTHENTICATION`; `action` is `LOGIN` or `PASSWORD_RESET`.
   - `actor_id` is the user's Cognito `sub`, as for every producer.
   - `event_id` is new for each call.
   - `payload` holds `user_pool_id`, `app_client_id` (when Cognito gives one), `trigger_source` and `datetime` (UTC). It holds no username, email, phone number or other user attribute.

   The auditor accepts this source, checks the payload, and requires each action to come from its trigger source. A `PASSWORD_RESET` from the triggers is shown in the activity feed, which lists password resets ([ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md) decision 3). A `LOGIN` is not, as sign-ins are not.
4. **A fixed time budget.** The handler sends within 3 seconds of being called, or less if Lambda's own timeout is closer.
   - Each SQS attempt is cut off by a 0.5-second connect timeout and a 1-second read timeout, with the SDK's own retries off.
   - After a failure it waits 50 ms, doubling each time, and tries again only while another whole attempt would still finish within the budget.
5. **Fail open, always.** The handler never throws. If the configuration is missing, the SDK does not load, the Cognito event cannot be read or every attempt fails, it does two things:
   - writes one JSON log line with `"msg": "AUDIT_SEND_FAILED"`, the audit event (when it could be built) and the error;
   - returns the Cognito event, so the sign-in or password reset goes ahead.

   The SDK is loaded by the first send rather than when Lambda creates the handler, so even a broken package cannot block a login.
6. **Replay.** `bank.cognito.Replay` (`make replay`) reads the output of `aws logs filter-log-events` and sends each logged audit event to the queue unchanged. The event keeps its `event_id`, so the auditor drops any it had stored after all.
7. **Packaging and network:**
   - **The zip:** the root Maven build packages the classes, with their dependencies as jars in `lib/`.
   - **The SQS client:** it is used without the SDK's default Netty and Apache HTTP clients; the JDK's URL connection client takes their place. Its region and credentials come straight from the variables Lambda sets, so a cold start does not search the SDK's default chains.
   - **The network:** the function runs outside the VPC and reaches SQS through its public regional endpoint over TLS, using its IAM role. ECS tasks already reach SQS through the NAT gateway rather than a VPC endpoint. The function needs no NAT, network interface or VPC endpoint.

## Rationale

- Measured, plain Java at 1,769 MB starts in about half a second. That leaves about 2.5 of the 3 seconds for retrying, well inside Cognito's limit.
- Staying on the baseline avoids a third language, a runtime exception and a separate CI toolchain. The triggers are built and tested in the same Maven build as the other Java services.
- Python started about 0.2 seconds faster at the same memory, too little to be worth a third language.
- SnapStart would cut the start further, but brings an alias, a keep-warm ping and a failure mode on idle environments, for time that is not needed.
- The memory is chosen for CPU. At 1,024 MB a cold start measured 0.9 seconds, which would also fit. 1,769 MB halves it for a cost that stays well under USD 0.10 a month.
- A source name of its own keeps these events apart from user-service's. It also lets the auditor check the fields Cognito provides, and later tie the name to one IAM role.

## Consequences

### Positive

- Logins and user-initiated password resets reach the audit chain, and an SQS outage does not lock users out.
- The same 3-second budget applies to every call, cold or warm.
- No new language: CI's existing `mvn -B verify` builds and tests the function, against LocalStack SQS through Testcontainers.

### Negative / Trade-offs

- **Memory:** the function is given far more memory than it uses, only for the CPU that comes with it.
- **Missing logins:** a login whose send fails is missing from the chain until an operator replays it.
- **Possible duplicates:** Cognito retries a trigger that times out. If the first call's send had already succeeded, the chain holds two `LOGIN` entries with different `event_id`s. The budget makes this unlikely.
- **An unmeasured part:** Lambda's own Java runtime start-up is not in the local figures. If the deployed cold start turns out much slower, SnapStart (Option C) is the next step.

## Security & Privacy Impact

- **What leaves the function:** events and logs carry the user's `sub` and the user pool and app client IDs only. Cognito's user attributes (email, phone number, name) are never sent or logged.
- **The function's IAM role:**
  - allows only `sqs:SendMessage` on `audit-insert`;
  - adds `kms:GenerateDataKey` and `kms:Decrypt` on the queue's key, if the queue uses a customer-managed key;
  - is listed as a producer in the queue policy.
- **Who can invoke it:** only Cognito, for this user pool.
- **Replay** uses its own role ([ADR-0027](ADR-0027-observability-baseline.md)), not the function's.
- **Sender verification:** once open item 18 is decided, the auditor can reject other producers that claim to be `COGNITO_TRIGGERS`.

## Cost Impact

At a few hundred sign-ins a day, mostly warm calls of a few milliseconds and cold starts of about half a second at 1.7 GB come to well under USD 0.10 a month. The function needs no provisioned concurrency, runs outside the VPC so adds no NAT data, and has one log group.

## Operational Impact

- **Infrastructure requirements** (Terraform, later; listed in `services/cognito-triggers/README.md`):
  - the function: `java25`, arm64, 1,769 MB, a 5-second timeout, handler `bank.cognito.Handler::handleRequest`, `AUDIT_QUEUE_URL`, outside the VPC;
  - the user pool's `PostAuthentication` and `PostConfirmation` triggers pointing at it, and Cognito's permission to invoke it;
  - its IAM role and the queue policy entry;
  - its log group;
  - alarms, routed to the Feature 1 owner (ADR-0027):
    - a metric filter on `{ $.msg = "AUDIT_SEND_FAILED" }`;
    - the function's `Errors` above 0;
    - its maximum `Duration` above 4 seconds;
  - a replay role.
- **CI:** the module is in the root `pom.xml`, so the existing backend job builds and tests it.
- **Versions** come from the root `pom.xml`: the AWS SDK and Spring Boot BOMs, the latter for Jackson, SLF4J, Lombok and the test libraries.
- **The auditor** accepts `COGNITO_TRIGGERS`; the auditor owner reviews that change.

## Validation

- **Unit tests:**
  - both trigger sources give the right message, and other sources send nothing;
  - no user attribute reaches the message or a log line;
  - every failure path returns the Cognito event and logs `AUDIT_SEND_FAILED`;
  - retries stop inside the budget.
- **With the real SDK:** a port that refuses connections and a server that never answers both end inside the budget.
- **Against LocalStack SQS (Testcontainers):**
  - a login's message arrives as the auditor expects;
  - a failed send, replayed from its log line, arrives with the same `event_id`.
- **The auditor's tests:** they accept the exact message the function sends, the same text as `AuditMessageTest`. They reject a wrong trigger source, a missing `sub` or user pool, and an unknown action. The feed lists the triggers' password resets but not their logins.
- **Measured locally (2026-10-13):** each package was unpacked read-only, with Docker CPU limits standing in for Lambda's CPU share. Java ran on Corretto 25.0.4 with Lambda's default level-1 JIT. Each figure runs from process start to the end of one cold call, which loads the code, creates the SQS client and sends once:

  | Memory | Java 25 | Python 3.14, for comparison |
  |---|---|---|
  | 256 MB | about 4.0 s | 2.4 s |
  | 512 MB | 1.9 s | 1.1 s |
  | 1,024 MB | 0.9 s | 0.6 s |
  | **1,769 MB** | **0.5 s** | 0.3 s |

  A warm call took about 6 ms in Java. The 256 MB Java figure is from a first prototype, and the others are from this function's zip. With the JVM's own default JIT settings, which Lambda uses only with SnapStart or provisioned concurrency, Java took about twice as long.
- **Once deployed (not yet run):**
  - a real login and a forgotten-password reset each add one chain entry;
  - the cold start's `Init Duration` plus `Duration`, from the function's `REPORT` log line, stays under 1.5 seconds;
  - with the queue policy denying the function, the login still succeeds and the `AUDIT_SEND_FAILED` alarm fires.

## References

- [ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md) decision 4, [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md) decision 3, [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 7, [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) decision 5, [ADR-0027](ADR-0027-observability-baseline.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)
- [Open item 18](../architecture/open-items.md#18-audit-sender-verification)
- [Security model: authentication events](../security/security-model.md#authentication-events)
- AWS documentation: [Customizing user pool workflows with Lambda triggers](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-identity-pools-working-with-aws-lambda-triggers.html); [Customize Java runtime startup behavior for Lambda functions](https://docs.aws.amazon.com/lambda/latest/dg/java-customization.html); [AWS Lambda adds support for Java 25](https://aws.amazon.com/about-aws/whats-new/2025/11/aws-lambda-java-25); [Activating and managing Lambda SnapStart](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-activate.html)
- `services/cognito-triggers/README.md`

## Revision history

- 2026-10-13: before merging, the runtime was changed from Python 3.14 to plain Java 25 by the decision owner, after Java's cold start was measured at the same memory sizes. The message, budget, fail-open behaviour and replay are unchanged.
