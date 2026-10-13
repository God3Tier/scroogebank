# ADR-0040: Cognito triggers in Python 3.14 on Lambda, sending within a fixed time budget

- Status: Accepted
- Decision date/time: 2026-10-13 14:30 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen (Feature 1 owner, who maintains the triggers)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/governance/adr-0040-trigger-runtime` (this record); `task/base-features/cognito-triggers` (implementation)
- Related issue/PR: TBD (pull requests from both branches to `dev`)
- Supersedes: N/A. It adds a Python exception to the runtime rule of [ADR-0017](ADR-0017-microservices-per-business-area.md), whose table does not list the triggers, and changes nothing else in it.
- Superseded by: N/A

## Context

[ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md) decision 4 and [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) decision 5 audit logins and user-initiated password resets with two Cognito Lambda triggers, `PostAuthentication` and `PostConfirmation_ConfirmForgotPassword`. They send each event straight to `audit-insert` and fail open: if the send fails, the event is logged for replay and the sign-in still succeeds. Nothing implements them yet, so Feature 1's login and password-change auditing is missing.

Cognito calls a trigger synchronously and waits 5 seconds, which cannot be changed. If the trigger times out, Cognito retries it, and after three failed attempts it returns an error. An error from the trigger is also returned to the user instead of tokens. The 5 seconds include the function's cold start.

`project_plan.md` §2.2 makes Java 25 the default runtime and requires an ADR for any other deployed runtime. ADR-0017 allows Go for the auditor, client-service and account-service. Plain Java 25 often takes 1–4 seconds to start on Lambda before its first SQS call, depending on memory. The Feature 1 owner maintains the triggers, and works in Java and Python but not Go.

## Decision Drivers

- Fit inside Cognito's 5 seconds, cold start included, with time left to retry.
- A login must never fail because auditing failed (ADR-0020 decision 4).
- The Feature 1 owner can maintain and explain the code.
- Few moving parts, and almost no cost.
- No personal data in audit events or logs.

## Options Considered

### Option A: Java 25 without SnapStart

- Pros: the baseline runtime; no ADR needed.
- Cons: a cold start of 1–4 seconds before the first send leaves little or no time to retry. A start that runs past 5 seconds makes Cognito retry while the user waits.

### Option B: Java 25 with SnapStart

- Pros: the baseline runtime. Lambda supports SnapStart on Java 25 at no extra cost, and a restore usually takes a few hundred milliseconds.
- Cons:
  - SnapStart works only on published versions, so Cognito must call an alias.
  - A version not called for 14 days loses its snapshot, and the next call fails with `SnapStartNotReadyException`, most likely failing that sign-in, unless a scheduled ping keeps it active. Environments in this project sit unused between demos ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)).
  - The restore time cannot be checked until the infrastructure exists.

### Option C: Go on Lambda's OS-only runtime

- Pros: the fastest start, and the same shape as transaction-ingest ([ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 7). The auditor's message types are already in Go.
- Cons: the owner does not know Go.

### Option D: Python 3.14 (chosen)

- Pros:
  - no snapshot to manage, so the start time is the same on every cold start and can be measured locally;
  - about 0.5 seconds for a cold start at 1,024 MB (Validation);
  - the owner knows it;
  - the function is about 230 lines with comments, and the replay tool about 90.
- Cons: a third deployed language, with its own CI job, linting and dependency updates. boto3 must be bundled and loaded, which is most of the cold start.

### Option E: provisioned concurrency, with any runtime

- Pros: no cold starts.
- Cons: billed all the time, about USD 5 or more a month per function, for a function called a few hundred times a day.

## Decision

1. **Runtime:** Python 3.14 on Lambda's managed `python3.14` runtime, on arm64. Python is allowed for the Cognito triggers only; any other Python component needs its own ADR. ADR-0017's Java 25 baseline and Go exception are unchanged.
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
   - The function has 1,024 MB of memory, for the CPU share that keeps a cold start near 0.5 seconds, and a 5-second Lambda timeout as a backstop.
5. **Fail open, always.** The handler never raises an error. If the configuration is missing, the SDK does not load, the Cognito event cannot be read or every attempt fails, it does two things:
   - writes one JSON log line with `"msg": "AUDIT_SEND_FAILED"`, the audit event (when it could be built) and the error;
   - returns the Cognito event, so the sign-in or password reset goes ahead.

   boto3 is loaded on the first send rather than at start-up, so even a broken package cannot block a login.
6. **Replay.** `python -m cognito_triggers.replay` reads the output of `aws logs filter-log-events` and sends each logged audit event to the queue unchanged. The event keeps its `event_id`, so the auditor drops any it had stored after all.
7. **Packaging and network:**
   - **The zip:** it bundles pinned versions of boto3 and its dependencies rather than using the runtime's copy, so every build is the same. It also carries their Python 3.14 bytecode, built in `python:3.14.8-slim-trixie`. Lambda's code folder is read-only, so without bytecode every cold start compiles boto3 from source.
   - **The network:** the function runs outside the VPC and reaches SQS through its public regional endpoint over TLS, using its IAM role. ECS tasks already reach SQS through the NAT gateway rather than a VPC endpoint. The function needs no NAT, network interface or VPC endpoint.

## Rationale

- With no snapshot, Python's cold start is predictable and was measured before any infrastructure exists. At 1,024 MB it leaves about 2.5 of the 3 seconds for retrying, which Java without SnapStart does not.
- SnapStart would keep Java, but brings an alias, a keep-warm ping and a failure mode on idle environments, and its start time could only be checked after deployment.
- Go starts fastest, but the person who maintains and explains the triggers does not know it. The function is small, so the owner's language matters more than Go's extra margin.
- Limiting Python to the triggers keeps the third language to one small deployable.
- A source name of its own keeps these events apart from user-service's. It also lets the auditor check the fields Cognito provides, and later tie the name to one IAM role.

## Consequences

### Positive

- Logins and user-initiated password resets reach the audit chain, and an SQS outage does not lock users out.
- The same 3-second budget applies to every call, cold or warm.
- The function is tested end to end against LocalStack SQS before any infrastructure exists.

### Negative / Trade-offs

- **Three languages:** Java, Go and Python are now deployed. CI gets a Python job (ruff and pytest), and Python and boto3 must be pinned and updated.
- **Missing logins:** a login whose send fails is missing from the chain until an operator replays it.
- **Possible duplicates:** Cognito retries a trigger that times out. If the first call's send had already succeeded, the chain holds two `LOGIN` entries with different `event_id`s. The budget makes this unlikely.
- **Package size:** the zip is about 18 MB, almost all of it boto3. Loading boto3 is most of the cold start.

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

At a few hundred sign-ins a day, a mostly warm call of a few milliseconds and a cold start of about half a second at 1 GB come to well under USD 0.10 a month. The function needs no provisioned concurrency, runs outside the VPC so adds no NAT data, and has one log group.

## Operational Impact

- **Infrastructure requirements** (Terraform, later; listed in `services/cognito-triggers/README.md`):
  - the function: `python3.14`, arm64, 1,024 MB, a 5-second timeout, handler `cognito_triggers.handler.lambda_handler`, `AUDIT_QUEUE_URL`, outside the VPC;
  - the user pool's `PostAuthentication` and `PostConfirmation` triggers pointing at it, and Cognito's permission to invoke it;
  - its IAM role and the queue policy entry;
  - its log group;
  - alarms, routed to the Feature 1 owner (ADR-0027):
    - a metric filter on `{ $.msg = "AUDIT_SEND_FAILED" }`;
    - the function's `Errors` above 0;
    - its maximum `Duration` above 4 seconds;
  - a replay role.
- **CI** runs `make -C services/cognito-triggers test`: ruff, pytest and LocalStack SQS.
- **Versions:** Python 3.14.8 and the dependency pins are kept in `services/cognito-triggers` (the `Makefile`, `docker-compose.test.yml` and `requirements.txt`).
- **The auditor** accepts `COGNITO_TRIGGERS`; the auditor owner reviews that change.

## Validation

- **Unit tests:**
  - both trigger sources give the right message, and other sources send nothing;
  - no user attribute reaches the message or a log line;
  - every failure path returns the Cognito event and logs `AUDIT_SEND_FAILED`;
  - retries stop inside the budget.
- **With the real SDK:** a port that refuses connections and a server that never answers both end inside the budget.
- **Against LocalStack SQS:**
  - a login's message arrives as the auditor expects;
  - a failed send, replayed from its log line, arrives with the same `event_id`.
- **The auditor's tests:** they accept the exact message the function sends. They reject a wrong trigger source, a missing `sub` or user pool, and an unknown action. The feed lists the triggers' password resets but not their logins.
- **Measured locally (2026-10-13):** the zip was unpacked read-only, with Docker CPU limits standing in for Lambda's CPU share. A cold call, which loads boto3, creates the client and sends once, took:

  | Memory | Cold call |
  |---|---|
  | 256 MB | 2.1 s |
  | 512 MB | 1.0 s |
  | 1,024 MB | 0.5 s |
  | 1,769 MB | 0.3 s |

  A warm call took 3 ms. Without bytecode in the zip, each cold call took 50–70% longer.
- **Once deployed (not yet run):**
  - a real login and a forgotten-password reset each add one chain entry;
  - the cold start's `Init Duration` plus `Duration`, from the function's `REPORT` log line, stays under 1.5 seconds;
  - with the queue policy denying the function, the login still succeeds and the `AUDIT_SEND_FAILED` alarm fires.

## References

- [ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md) decision 4, [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md) decision 3, [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 7, [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) decision 5, [ADR-0027](ADR-0027-observability-baseline.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)
- [Open item 7](../architecture/open-items.md#7-go-runtime-exception), [open item 18](../architecture/open-items.md#18-audit-sender-verification)
- [Security model: authentication events](../security/security-model.md#authentication-events)
- AWS documentation: [Customizing user pool workflows with Lambda triggers](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-identity-pools-working-with-aws-lambda-triggers.html); [AWS Lambda adds support for Python 3.14](https://aws.amazon.com/about-aws/whats-new/2025/11/aws-lambda-python-314); [Activating and managing Lambda SnapStart](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-activate.html)
- `services/cognito-triggers/README.md`
