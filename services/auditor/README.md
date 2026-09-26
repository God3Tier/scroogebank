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

Start the supporting services:

```sh
docker compose -f compose.yaml up -d postgres kafka
```

Run the auditor server from the `services/auditor` directory:

```sh
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
docker compose -f compose.yaml down
```

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
