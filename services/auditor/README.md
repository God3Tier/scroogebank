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
docker compose up -d postgres localstack
```

Run the auditor server from the `services/auditor` directory. Since
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
PORT=9090 go run ./src/serverd
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

Set `AUDITOR_TLS_CERT_FILE` and `AUDITOR_TLS_KEY_FILE` to serve both ports over TLS, as the target design requires; leave both unset only for local development.

### Corrupted audit log endpoints

The auditor exposes these HTTP endpoints, for admins only, for reviewing and removing corrupted
audit entries. Which of them should exist is still open ([ADR-0022](../../docs/adr/ADR-0022-audit-tamper-resistance-and-crypto-shredding.md), [open item 13](../../docs/architecture/open-items.md#13-tamper-findings-and-chain-verify-on-dev)):

| Method | Path | Behavior |
|---|---|---|
| `GET` | `/api/corrupted-logs` | Return all decodable corrupted audit logs as JSON. |
| `POST` | `/api/corrupted-logs` | Store a corrupted log. The request body contains `id` (UUID) and `payload` (the original audit log). The server sets `date_of_detect`. |
| `DELETE` | `/api/corrupted-logs/{id}` | Delete the entry with the given UUID. |

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

## Run tests

The recommended test command creates a disposable PostgreSQL container, loads `data/audit_log_table.sql`, generates SQLBoiler models, prefixes generated files with `mocks_`, and runs all Go tests in a Go container:

```sh
make test
```

The test database is removed automatically when the command finishes, including after a test failure.

To run only the Go tests without database setup:

```sh
cd services/auditor
go test ./...
```

To run the repository tests with coverage:

```sh
cd services/auditor
go test ./src/internal/repository -cover
```

## Generate SQLBoiler models

SQLBoiler runs inside Docker against a disposable PostgreSQL database:

```sh
make sqlboiler
```

Generated Go files are written under `services/auditor/src/internal/repository/orm` and renamed with the `mocks_` prefix. Existing files already beginning with `mocks_` are not renamed again.

## Generate mockery mocks

Build the generator image and generate mocks for the configured internal packages:

```sh
make mockery
```

To pass additional mockery arguments:

```sh
make mockery MOCKERY_ARGS="--log-level debug"
```

The mockery configuration is in `.mockery.yml` and targets the service's interface packages.

## Generate protobuf code

The gRPC contract is `proto/audit/v1/audit.proto` at the repository root, shared with its callers ([ADR-0021](../../docs/adr/ADR-0021-audit-read-path-grpc-service-connect.md)). The generated Go code goes to `src/gen` and is git-ignored. Generate it before building or testing the server and controller packages; this runs `protoc` in the tools image, so nothing needs to be installed locally:

```sh
make proto
```

## gRPC API

`AuditService` (package `audit.v1`) is served on `GRPC_PORT`. Other services call it over the internal network only; it is not exposed through API Gateway.

| RPC | Purpose |
|---|---|
| `ListActivities` | The activity feed's entries, newest first, filtered by `actor_id` (a Cognito `sub`) and a `from`/`to` time range. Only changes are listed: creating, updating, deleting or disabling a record, changing a role and resetting a password, from `USER_MANAGEMENT` and `CRM` (`models.ActivityActions`), and not failed attempts (`status_code` 400 or above). Reads, sign-outs, dashboard views and events from ingestion or chain-verify stay in the audit log. An admin may ask for any actor or none; an agent always gets only their own entries, and asking for another actor is `PERMISSION_DENIED`. `page_size` must be 1 to 100. Pass the response's `next_page_token` as `page_token` to get the next page; an empty token means there are no more pages. |

user-service generates its Java client from the same file at build time. Change the contract additively only: add fields and RPCs with new numbers, never renumber, reuse or remove one. CI runs `buf lint` and `buf breaking` against the pull request's base branch.

### `actor_id` convention

Every producer sets an event's `actor_id` to the acting user's **Cognito `sub`**, taken from the verified token. The client service stores the same value as a client's `created_by_agent_id`. Work that no user started uses a `system:` prefix, for example `system:root-admin-initializer`. `ListActivities` compares `actor_id` by exact text, so an event carrying any other id, such as user-service's `users.id`, will not appear in that user's activity feed.

## Build and run the production image

Build the image:

Build it from the repository root, because the gRPC code is generated from the shared `proto/` directory. The ORM must have been generated first (`make sqlboiler`), as before.

```sh
docker build -f services/auditor/Dockerfile -t auditor .
```

Run it:

```sh
docker run --rm -p 8080:8080 auditor
```

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
