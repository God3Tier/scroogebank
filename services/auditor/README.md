# Auditor service

Go service for recording and reading audit-chain entries.

## Prerequisites

Install the following on your computer:

- Go 1.27.1 or newer
- Docker Desktop with Docker Compose
- `make`

PostgreSQL, SQLBoiler, mockery, and the test database are provided through Docker. You do not need to install PostgreSQL, `psql`, SQLBoiler, or mockery locally for the commands below.

Run all commands from the repository root:

```sh
cd project-2026-27t1-g3-t6
```

## Run the service locally

Start the supporting services:

```sh
docker compose up -d postgres kafka
```

Run the auditor server from the `services/auditor` directory:

```sh
cd services/auditor
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

Stop the supporting services when finished:

```sh
cd ../..
docker compose down
```

## Run tests

The recommended test command creates a disposable PostgreSQL container, loads `services/auditor/data/create_table.sql`, generates SQLBoiler models, prefixes generated files with `mocks_`, and runs all Go tests in a Go container:

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

The mockery configuration is in `services/auditor/.mockery.yml`. Generated protobuf code under `src/gen` is excluded from mock generation.

## Generate protobuf code

The protobuf compiler and Go protobuf plugins must be installed locally for this target:

```sh
make protobuf_setup
```

## gRPC API

`proto/audit_request.proto` defines `AuditService`. Other services call it over the internal network only. It is not exposed through API Gateway.

| RPC | Purpose |
|---|---|
| `GetAuditLog` | All entries from one source service |
| `ListAuditLogs` | Entries filtered by any of `source_service`, `actor_id` and a `from`/`to` time range, newest first. `page_size` must be 1 to 100. Pass the response's `next_before_sequence_no` as `before_sequence_no` to get the next page; `0` means there are no more pages. |

The user service generates its Java client stubs from this same file at build time, so a change here must stay backward compatible. Add fields and RPCs; do not renumber or remove them.

### `actor_id` convention

Every producer sets an event's `actor_id` to the acting user's **agent id**: the user service's `users.id` UUID, the same value the client service stores in `created_by_agent_id`. Work that no user started uses `SYSTEM`. `ListAuditLogs` compares `actor_id` by exact text, and the admin activity feed filters on it (`GET /api/activities?agentId=<users.id>`). An event carrying any other id, such as the Cognito `sub`, will not match that agent's filter, and nothing reports the mismatch. A producer that has only the caller's token must map the token's `sub` to `users.id` first; the user service does this in `AuditRecorder`, looking the user up by `idp_sub`.

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
