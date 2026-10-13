# Auditor service

Go service for recording and reading audit-chain entries.

## Prerequisites

Install the following on your computer:

- Go 1.27.1 or newer
- Docker Desktop with Docker Compose
- `make`

PostgreSQL, SQLBoiler, mockery, and the test database are provided through Docker. You do not need to install PostgreSQL, `psql`, SQLBoiler, or mockery locally for the commands below.

Run service commands from this directory:

```sh
cd services/auditor
```

## Run the service locally

Start the supporting services. `localstack` stands in for AWS SQS — see
[ADR-0004](../../docs/adr/ADR-0004-auditor-kafka-to-sqs-migration.md) for why
the auditor moved off Kafka. It creates the `audit-insert` and
`audit-insert-dlq` queues automatically on startup:

```sh
cd services/auditor
docker compose up -d postgres localstack
```

`make run` does all of this in one step, with LocalStack's queue URLs as defaults: it starts both containers, loads the schema if it is missing, generates the gRPC code and runs the server. Pass the Cognito settings: `make run COGNITO_ISSUER_URI=... COGNITO_APP_CLIENT_ID=...`.

To run it by hand instead, start the server from the `services/auditor` directory. Since
LocalStack doesn't check AWS credentials, any non-empty values work:

```sh
cd services/auditor
export AWS_REGION=us-east-1
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AUDIT_QUEUE_URL=http://localhost:4566/000000000000/audit-insert
export AUDIT_DLQ_URL=http://localhost:4566/000000000000/audit-insert-dlq
export COGNITO_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id>
export COGNITO_APP_CLIENT_ID=<app-client-id>
make proto   # once, and after the contract changes
go run ./src/serverd
```

The auditor loads the user pool's signing keys at startup and refuses to start without the two Cognito settings. Every call to `/api` and to the gRPC port must carry a CRM user's Cognito access token (see [Access](#access)).

The server listens on port `8080` by default. To use another port:

```sh
HTTP_PORT=9090 go run ./src/serverd
```

Check the health endpoint:

```sh
curl http://localhost:8080/health
```

Expected response:

```json
{"status":"ok"}
```

### Access

Only other CRM services call the auditor, and each forwards the token of the user it is acting for ([ADR-0021](../../docs/adr/ADR-0021-audit-read-path-grpc-service-connect.md)). The auditor checks the token itself: its signature against the user pool's keys, issuer, app client, expiry and `token_use=access`.

| Port | Who is admitted |
|---|---|
| HTTP `/health` | Anyone |
| HTTP `/api/corrupted-logs` | An `ADMIN`'s token in `Authorization: Bearer`; otherwise `401` (no or an invalid token) or `403` (not an admin). user-service forwards it. |
| gRPC `AuditService` | An `ADMIN`'s or `AGENT`'s token in the `authorization` metadata; otherwise `UNAUTHENTICATED` or `PERMISSION_DENIED`. An agent can list only their own activities. |

Only user tokens are accepted so far. ADR-0021 also lets a background job with no user call with a Cognito client-credentials token carrying an `audit/read` scope; that is not built yet, because no such caller exists. Such a token has no CRM group, so it is refused with `PERMISSION_DENIED`.

Set `AUDITOR_TLS_CERT_FILE` and `AUDITOR_TLS_KEY_FILE` to serve both ports over TLS, as the target design requires; leave both unset only for local development.

### Corrupted audit log endpoints

`corrupted_audit_log` holds the tamper findings chain-verify records ([Audit chain and chain-verify](#audit-chain-and-chain-verify)). The auditor exposes these HTTP endpoints, for admins only, for reviewing them. Which of them should exist is still open ([ADR-0022](../../docs/adr/ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) decision 4, [open item 13](../../docs/architecture/open-items.md#13-tamper-findings-and-chain-verify-on-dev)):

| Method | Path | Behavior |
|---|---|---|
| `GET` | `/api/corrupted-logs` | Return all decodable findings as JSON. A finding chain-verify recorded also carries `audit_log_id` (the affected entry) and `reason`. |
| `POST` | `/api/corrupted-logs` | Store a finding. The request body contains `id` (UUID) and `payload` (the original audit log); it cannot set `audit_log_id` or `reason`. The server sets `date_of_detect`. |
| `DELETE` | `/api/corrupted-logs/{id}` | Delete the finding with the given UUID. If the problem is still in the chain, chain-verify records it again on its next run. |

Malformed JSON or UUIDs return `400`, a missing entry returns `404`, and
unexpected storage failures return `500`.

```sh
TOKEN=<an admin's Cognito access token>
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/corrupted-logs
curl -X POST http://localhost:8080/api/corrupted-logs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"id":"123e4567-e89b-12d3-a456-426614174000","payload":{"Id":"audit-1","EventType":"CRUD","Action":"UPDATE"}}'
curl -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/corrupted-logs/123e4567-e89b-12d3-a456-426614174000
```

Send a test event to the local queue (requires the `awslocal` CLI, or use
`aws --endpoint-url=http://localhost:4566` instead):

```sh
awslocal sqs send-message \
  --queue-url http://localhost:4566/000000000000/audit-insert \
  --message-body '{"event_id":"evt-1","event_type":"CRUD","action":"UPDATE","source_service":"CRM","actor_id":"agent-1","payload":{"agent_id":"AGT-1","client_id":"CLI-1","attribute_name":"Address","before_value":"A","after_value":"B","datetime":"2026-09-24T00:00:00Z"}}'
```

User-management producers use the same `CRUD` event type with
`source_service=USER_MANAGEMENT`. Their payload records the resource type and
ID, endpoint, HTTP method, response status, timestamp, and optional role; it
must not contain passwords, names, email addresses, or request bodies. Each
resource type allows these actions:

| `resource_type` | Actions |
|---|---|
| `USER` | `CREATE`, `READ`, `UPDATE`, `DISABLE`, `PASSWORD_RESET`, `ROLE_CHANGE`, `LOGOUT` |
| `ACCOUNT` (an account's transactions) | `READ` |
| `CORRUPTED_AUDIT_LOG` | `CREATE`, `READ`, `DELETE` |

A successful event must carry `resource_id`, except a `READ` of
`CORRUPTED_AUDIT_LOG`, which reads the whole list.

The Cognito triggers (`services/cognito-triggers`, [ADR-0040](../../docs/adr/ADR-0040-cognito-triggers-java-lambda.md))
send `source_service=COGNITO_TRIGGERS` with `event_type=AUTHENTICATION`. The
actor is the user's Cognito `sub`, and the payload names the user pool, the
app client (optional), the Cognito trigger source and the time, never the
user's attributes. Each action must come from its own trigger source:

| `action` | `trigger_source` |
|---|---|
| `LOGIN` | `PostAuthentication_Authentication` |
| `PASSWORD_RESET` (a user's own forgotten-password reset) | `PostConfirmation_ConfirmForgotPassword` |

The auditor long-polls the queue (up to 20s), so it should pick the message
up, store it, and delete it shortly after.

Stop the supporting services when finished:

```sh
docker compose -f compose.yaml down
```

### Environment variables

| Variable | Purpose |
|---|---|
| `AWS_REGION` | AWS region for the SQS client |
| `AUDIT_QUEUE_URL` | URL of the `audit-insert` queue |
| `AUDIT_DLQ_URL` | URL of the `audit-insert-dlq` queue |
| `AWS_ENDPOINT_URL` | Set only for LocalStack; omit in AWS so the SDK talks to real SQS |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | Local/LocalStack only. In AWS, credentials come from the task's IAM role — never hardcode these |
| `COGNITO_ISSUER_URI` | Required. The Cognito user pool's issuer URL, whose signing keys verify callers' tokens |
| `COGNITO_APP_CLIENT_ID` | Required. The CRM app client that callers' tokens must be issued to |
| `COGNITO_API_AUDIENCE` | Optional. The expected `aud`, if access tokens carry one |
| `AUDITOR_TLS_CERT_FILE` / `AUDITOR_TLS_KEY_FILE` | Certificate and key for TLS on both ports. Set both in deployed environments; unset only locally |

## Audit chain and chain-verify

The auditor appends each event to a hash chain in `audit_log`, and the daily chain-verify task checks it ([ADR-0022](../../docs/adr/ADR-0022-audit-tamper-resistance-and-crypto-shredding.md)).

**The hash.** Each entry's `hash` is SHA-256, in lowercase hex, over the version tag `audit-chain-v1` and then these fields, in order:
- `id`, `sequence_no` and `event_id`;
- `event_type`, `action` and `source_service`;
- `actor_id`, `timestamp` and `payload`;
- `parent_hash`, the previous entry's hash (empty for the first entry).

Each item is written as its length in bytes, a colon, its bytes and a comma (`src/internal/chain`).

The hash covers the entry exactly as stored: the payload is `json`, not `jsonb` (which rewrites it), and `timestamp` is `TIMESTAMPTZ` in UTC at microsecond precision. Entries are never updated or deleted. Writers take turns through an advisory lock, so sequence numbers start at 1 and have no gaps.

**chain-verify** (`src/chainverify`) reads the whole chain once and exits. For each entry it checks the hash, the link to the previous entry and the sequence number. It also checks the entry the latest anchor names (see **Anchors** below). It records each problem in `corrupted_audit_log`, once per entry and reason:

| Reason | Meaning |
|---|---|
| `HASH_MISMATCH` | The entry was changed after it was written. |
| `PARENT_MISMATCH` | An entry before it was deleted, added, or changed and given a new hash. |
| `SEQUENCE_GAP` | Entries before it are missing. |
| `ANCHOR_MISMATCH` | The entry the latest anchor names is gone or has another id or hash: the chain was rewritten with every hash recomputed, or cut back below the anchor. For an entry that is gone, the finding holds the anchor's sequence number, id and hash. |

chain-verify never changes the chain, so a problem stays and is found again on every run. Only a new problem is logged, as `CHAIN_TAMPER_DETECTED reason=... sequence_no=... audit_log_id=...`, for the alarm.

| Exit status | Meaning |
|---|---|
| 0 | No new finding was recorded |
| 1 | The check could not finish |
| 2 | A new finding was recorded |

To check the local chain, start the database and LocalStack with `make run`, and generate the ORM (`make sqlboiler`) first:

```sh
make chain-verify
```

| Setting | Purpose |
|---|---|
| `DB_NAME`, `HOST`, `USER`, `PASSWORD`, `SSLMODE` | audit-db, as for the server |
| `CHAIN_ANCHOR_BUCKET` | Required. The S3 Object Lock bucket for the anchors; chain-verify does not run without it |
| `AWS_REGION` | AWS region for the S3 client |
| `AWS_ENDPOINT_URL` | Set only for LocalStack; requests then name the bucket in the path |

**Anchors.** Someone with database access could rewrite the whole chain with every hash recomputed, which the checks above cannot see. So each run ends by writing the chain head (sequence number, id and hash) to the S3 Object Lock bucket (`src/internal/repository/anchor`):
- **One new object per run,** named `chain-head/<UTC time>.json`, so keys sort in the order they were written. The next run reads back the newest.
- **Locked on write:** each object is locked in governance mode for 7 days. The lock is set on each write, not left to the bucket's default, so S3 refuses the write, and the run fails, on a bucket without Object Lock.
- **Written even when problems were found:** the chain is never repaired, so holding back would leave every later entry unanchored. An empty chain writes nothing.

Entries written after the last anchor are protected only by the next one, so up to a day of the newest entries can be removed undetected.

LocalStack (`localstack/init-anchor-bucket.sh`) creates the bucket `chain-anchors` with Object Lock enabled. If your LocalStack container was started before the bucket existed, restart it: `docker compose restart localstack`. Unlike S3, LocalStack accepts a lock on a bucket without Object Lock, so a misconfigured bucket fails only in AWS.

**Database roles.** `data/schema.sql` creates two roles that cannot log in themselves (ADR-0022 decision 2):

| Role | `audit_log` | `corrupted_audit_log` | Used by |
|---|---|---|---|
| `auditor_service` | `SELECT`, `INSERT` | `SELECT`, `INSERT`, `DELETE` | the auditor |
| `auditor_chain_verify` | `SELECT` | `SELECT`, `INSERT` | chain-verify |

Locally, `make run` and `make chain-verify` connect as `postgres`. The tests log in with each role and check two things:
- the role is enough for its job;
- `UPDATE`, `DELETE` and `TRUNCATE` on `audit_log` are refused.

**Existing local databases.** The hash format and the schema changed, so a database created before them no longer works. `make run` loads the schema only into an empty database, so recreate yours: run `make db-reset`, then `make run`.

**Infrastructure requirements**, for when the infrastructure is built (ADR-0022, [ADR-0028](../../docs/adr/ADR-0028-cicd-migrations-terraform.md)):
- **Database logins:** the schema-migration task owns the tables. It creates one login for the auditor holding `auditor_service`, and one for chain-verify holding `auditor_chain_verify`. Neither service connects as the owner or the master user.
- **chain-verify's task:**
  - the ECS task definition runs the auditor image with the entry point `/chain-verify`;
  - it has its own task role and database secret;
  - EventBridge Scheduler starts it daily.
- **Alarms:** an SNS alarm from a CloudWatch metric filter on `CHAIN_TAMPER_DETECTED` in chain-verify's logs, and one when the task exits with status 1.
- **The anchor bucket:**
  - created with Object Lock enabled, which also turns on versioning;
  - chain-verify's task role gets `s3:PutObject`, `s3:PutObjectRetention`, `s3:GetObject` and `s3:ListBucket` on it. No other principal may write to it;
  - `s3:BypassGovernanceRetention` only for the teardown role;
  - CloudTrail data events on the bucket;
  - an S3 gateway endpoint for the private subnets.

## Run tests

The recommended test command creates a disposable PostgreSQL container loaded with `data/schema.sql`, generates the gRPC code, the SQLBoiler models and the mocks (all git-ignored), and runs all Go tests in a Go container. It works from a clean checkout:

```sh
make test
```

The test database is removed automatically when the command finishes, including after a test failure.

To run the Go tests directly, generate the code first (`make proto`, `make sqlboiler`, `make mockery`). The repository and chain-verify tests also need PostgreSQL, reached through `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD` and `DB_NAME`. The user must be able to create roles and databases: the tests create logins holding each role, and chain-verify's tests use a database of their own, `chain_verify_test`. The anchor tests run against S3 only when `CHAIN_ANCHOR_TEST_ENDPOINT` names one; `make test` points it at a LocalStack container.

```sh
cd services/auditor
go test ./...
```

To run the repository tests with coverage:

```sh
cd services/auditor
go test ./src/internal/repository/... -cover
```

## Generate SQLBoiler models

SQLBoiler runs inside Docker against a disposable PostgreSQL database:

```sh
make sqlboiler
```

Generated Go files are written under `services/auditor/src/internal/repository/orm`, which is git-ignored.

## Generate mockery mocks

Build the generator image and generate mocks for the configured internal packages:

```sh
make mockery
```

To pass additional mockery arguments:

```sh
make mockery MOCKERY_ARGS="--log-level debug"
```

The mockery configuration is in `.mockery.yml` and targets the service's interface packages. Mockery loads the packages first, so it needs the gRPC code and the ORM (`make proto`, `make sqlboiler`). `make mockery` deletes the old `mocks.go` files before regenerating: a mock left over from a removed method would not compile, and mockery could not load its package.

## Generate protobuf code

The gRPC contract is `proto/audit/v1/audit.proto` at the repository root, shared with its callers ([ADR-0021](../../docs/adr/ADR-0021-audit-read-path-grpc-service-connect.md)). The generated Go code goes to `src/gen` and is git-ignored. Generate it before building or testing the server and controller packages; this runs `protoc` in the tools image, so nothing needs to be installed locally:

```sh
make proto
```

## gRPC API

`AuditService` (package `audit.v1`) is served on `GRPC_PORT`. Other services call it over the internal network only; it is not exposed through API Gateway.

| RPC | Purpose |
|---|---|
| `ListActivities` | The activity feed's entries, newest first, filtered by `actor_id` (a Cognito `sub`) and a `from`/`to` time range. Only changes are listed: creating, updating, deleting or disabling a record, changing a role and resetting a password, from `USER_MANAGEMENT` and `CRM`, plus a user's own password reset from `COGNITO_TRIGGERS` (`models.ActivityActions`), and not failed attempts (`status_code` 400 or above). Reads, sign-ins, sign-outs, dashboard views and events from ingestion or chain-verify stay in the audit log. An admin may ask for any actor or none; an agent always gets only their own entries, and asking for another actor is `PERMISSION_DENIED`. `page_size` must be 1 to 100. Pass the response's `next_page_token` as `page_token` to get the next page; an empty token means there are no more pages. |

user-service generates its Java client from the same file at build time. Change the contract additively only: add fields and RPCs with new numbers, never renumber, reuse or remove one. CI runs `buf lint` and `buf breaking` against the pull request's base branch.

### `actor_id` convention

Every producer sets an event's `actor_id` to the acting user's **Cognito `sub`**, taken from the verified token. The client service stores the same value as a client's `created_by_agent_id`. Work that no user started uses a `system:` prefix, for example `system:root-admin-initializer`. `ListActivities` compares `actor_id` by exact text, so an event carrying any other id, such as user-service's `users.id`, will not appear in that user's activity feed.

## Build and run the production image

Build the image from the repository root, because the gRPC code is generated from the shared `proto/` directory. The ORM must have been generated first (`make sqlboiler`), as before.

```sh
docker build -f services/auditor/Dockerfile -t auditor .
```

Run it:

```sh
docker run --rm -p 8080:8080 auditor
```

The image also holds chain-verify. To run that instead, pass the database settings:

```sh
docker run --rm --entrypoint /chain-verify -e HOST=... -e USER=... -e PASSWORD=... -e DB_NAME=... -e SSLMODE=require   -e AWS_REGION=... -e CHAIN_ANCHOR_BUCKET=... auditor
```

**Release order with user-service.** user-service forwards the caller's access token, which this service now requires (ADR-0021), so release user-service first when both change together. The opposite holds for new audit event types: release the Auditor first, or user-service's new events go to the dead-letter queue.

## Useful Docker commands

View PostgreSQL logs:

```sh
make db-logs
```

Start the regular development dependencies:

```sh
make db-up
```

Stop them while keeping the PostgreSQL volume:

```sh
make db-down
```

Stop them and delete the PostgreSQL volume:

```sh
make db-reset
```
