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
go run ./src/serverd
```

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

### Corrupted audit log endpoints

The auditor exposes these HTTP endpoints for reviewing and removing corrupted
audit entries:

| Method | Path | Behavior |
|---|---|---|
| `GET` | `/api/corrupted-logs` | Return all decodable corrupted audit logs as JSON. |
| `POST` | `/api/corrupted-logs` | Store a corrupted log. The request body contains `id` (UUID) and `payload` (the original audit log). The server sets `date_of_detect`. |
| `DELETE` | `/api/corrupted-logs/{id}` | Delete the entry with the given UUID. |

Malformed JSON or UUIDs return `400`, a missing entry returns `404`, and
unexpected storage failures return `500`.

```sh
curl http://localhost:8080/api/corrupted-logs
curl -X POST http://localhost:8080/api/corrupted-logs \
  -H 'Content-Type: application/json' \
  -d '{"id":"123e4567-e89b-12d3-a456-426614174000","payload":{"Id":"audit-1","EventType":"CRUD","Action":"UPDATE"}}'
curl -X DELETE http://localhost:8080/api/corrupted-logs/123e4567-e89b-12d3-a456-426614174000
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

The protobuf compiler and Go protobuf plugins must be installed locally. Run this from `services/auditor`; it generates Go files under `src/gen`, matching the imports used by the server and controller:

```sh
make proto
```

## Build and run the production image

Build the image:

```sh
docker build -t auditor services/auditor
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
