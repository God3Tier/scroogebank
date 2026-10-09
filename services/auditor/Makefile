DB_USER      := postgres
DB_PASS      := postgres
DB_NAME      := myapp
DB_HOST      := localhost
DB_PORT      := 5432
HTTP_PORT    ?= 8080
GRPC_PORT    ?= :9091

SCHEMA_FILE  := data/schema.sql
ORM_DIR      := src/internal/repository/orm
# The gRPC contract is shared with its callers in the repository's proto/
# directory (ADR-0021).
PROTO_DIR    := $(abspath $(CURDIR)/../../proto)
PROTO_SRC    := audit/v1/audit.proto
PROTO_OUT    := src

COMPOSE := docker compose -f compose.yaml
TEST_COMPOSE := docker compose -f docker-compose.test.yml -p auditor-test

TOOLS_IMAGE  ?= auditor-tools
TOOLS_MOUNT  := -v "$(CURDIR)":/workspace -w /workspace
TOOLS_RUN    := docker run --rm --platform linux/amd64 $(TOOLS_MOUNT) $(TOOLS_IMAGE)
DOCKER_BUILD_PROGRESS ?= plain

MOCKERY_ARGS ?=
# Callers' tokens are checked against this user pool and app client (ADR-0021).
COGNITO_ISSUER_URI ?=
COGNITO_APP_CLIENT_ID ?=
# make run's SQS is the LocalStack container in compose.yaml. LocalStack checks
# no credentials, so the key is a placeholder, not a secret.
AWS_REGION       ?= us-east-1
AWS_ENDPOINT_URL ?= http://localhost:4566
AUDIT_QUEUE_URL  ?= http://localhost:4566/000000000000/audit-insert
AUDIT_DLQ_URL    ?= http://localhost:4566/000000000000/audit-insert-dlq
LOCALSTACK_KEY   := test

.PHONY: \
	db-up db-down db-logs db-reset load-schema psql \
	run down \
	tools-image mockery proto \
	test-db-up test-db-down generate-orm sqlboiler \
	test test-clean all


# ============================================================
# Local development PostgreSQL
# compose.yaml
# ============================================================

## Start PostgreSQL and LocalStack (SQS), load the database schema if missing,
## generate the gRPC code, and run the Auditor
run: proto
	$(COMPOSE) up -d postgres localstack
	@until $(COMPOSE) exec -T postgres pg_isready -U $(DB_USER) -d $(DB_NAME) >/dev/null 2>&1; do sleep 1; done
	@until $(COMPOSE) exec -T localstack awslocal sqs get-queue-url --queue-name audit-insert >/dev/null 2>&1; do sleep 2; done
	@if ! $(COMPOSE) exec -T postgres psql -U $(DB_USER) -d $(DB_NAME) -tAc "SELECT to_regclass('public.audit_log') IS NOT NULL" | grep -qx t; then \
		echo "Loading Auditor database schema..."; \
		$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) < $(SCHEMA_FILE); \
	fi
	HOST=localhost USER=$(DB_USER) PASSWORD=$(DB_PASS) DB_NAME=$(DB_NAME) SSLMODE=disable GRPC_PORT=$(GRPC_PORT) HTTP_PORT=$(HTTP_PORT) COGNITO_ISSUER_URI="$(COGNITO_ISSUER_URI)" COGNITO_APP_CLIENT_ID="$(COGNITO_APP_CLIENT_ID)" AWS_REGION=$(AWS_REGION) AWS_ENDPOINT_URL=$(AWS_ENDPOINT_URL) AWS_ACCESS_KEY_ID=$(LOCALSTACK_KEY) AWS_SECRET_ACCESS_KEY=$(LOCALSTACK_KEY) AUDIT_QUEUE_URL=$(AUDIT_QUEUE_URL) AUDIT_DLQ_URL=$(AUDIT_DLQ_URL) go run ./src/serverd

db-up:
	$(COMPOSE) up -d postgres
	@echo "Waiting for PostgreSQL..."
	@until $(COMPOSE) exec -T postgres \
		pg_isready -U $(DB_USER) -d $(DB_NAME) >/dev/null 2>&1; do \
		sleep 1; \
	done
	@echo "PostgreSQL is ready."

db-down:
	$(COMPOSE) down --remove-orphans
	$(TEST_COMPOSE) down --remove-orphans

## Stop all Auditor Compose containers and networks, keeping database volumes
down: db-down

db-reset:
	$(COMPOSE) down -v

db-logs:
	$(COMPOSE) logs -f postgres

load-schema:
	PGPASSWORD=$(DB_PASS) psql \
		-h $(DB_HOST) \
		-p $(DB_PORT) \
		-U $(DB_USER) \
		-d $(DB_NAME) \
		-f $(SCHEMA_FILE)

psql:
	PGPASSWORD=$(DB_PASS) psql \
		-h $(DB_HOST) \
		-p $(DB_PORT) \
		-U $(DB_USER) \
		-d $(DB_NAME)


# ============================================================
# Tooling / code generation
# ============================================================

tools-image:
	docker build --progress=$(DOCKER_BUILD_PROGRESS) --platform linux/amd64 \
		--target tools \
		-t $(TOOLS_IMAGE) \
		.

## Regenerate the mocks (git-ignored) from scratch. Old mocks are removed first:
## one left over from a removed method would stop mockery loading the package.
## Needs the gRPC code and the ORM (make proto, make sqlboiler).
mockery: tools-image
	$(TOOLS_RUN) sh -c 'find src -name mocks.go -delete && mockery $(MOCKERY_ARGS)'

## Generate the gRPC code into src/gen (git-ignored) from the shared contract.
## Runs in the tools image, so protoc need not be installed locally.
proto: tools-image
	docker run --rm --platform linux/amd64 $(TOOLS_MOUNT) -v "$(PROTO_DIR)":/proto:ro $(TOOLS_IMAGE) \
		protoc -I /proto -I /usr/include \
			--go_out=$(PROTO_OUT) --go_opt=module=auditor/src \
			--go-grpc_out=$(PROTO_OUT) --go-grpc_opt=module=auditor/src \
			/proto/$(PROTO_SRC)


# ============================================================
# Disposable test PostgreSQL
# docker-compose.test.yml
# ============================================================

test-db-up:
	@echo "Cleaning previous test environment..."
	$(TEST_COMPOSE) down -v --remove-orphans >/dev/null 2>&1 || true

	@echo "Starting test PostgreSQL..."
	$(TEST_COMPOSE) up -d postgres

	@echo "Waiting for test PostgreSQL..."
	@until $(TEST_COMPOSE) exec -T postgres \
		pg_isready -U $(DB_USER) -d $(DB_NAME) >/dev/null 2>&1; do \
		sleep 1; \
	done

	@echo "Test PostgreSQL is ready."

	@echo "Loading database schema..."
	$(TEST_COMPOSE) exec -T postgres \
		psql \
		-U $(DB_USER) \
		-d $(DB_NAME) \
		< $(SCHEMA_FILE)

	@echo "Database schema loaded."


test-db-down:
	@echo "Stopping test environment..."
	$(TEST_COMPOSE) down -v --remove-orphans


# ============================================================
# SQLBoiler
# ============================================================

generate-orm: test-db-up
	@echo "Generating SQLBoiler ORM models..."

	$(TEST_COMPOSE) run --rm -T tools \
		sqlboiler \
		-c sqlboiler.toml \
		psql \
		-o src/internal/repository/orm \
		--wipe

	@echo "ORM generation complete."


sqlboiler:
	@trap '$(TEST_COMPOSE) down -v --remove-orphans' EXIT; \
	$(MAKE) generate-orm


# ============================================================
# Tests
# ============================================================

test:
	@trap '$(TEST_COMPOSE) down -v --remove-orphans' EXIT; \
	echo "========================================"; \
	echo "Starting integration test environment"; \
	echo "========================================"; \
	$(MAKE) proto; \
	$(MAKE) generate-orm; \
	$(MAKE) mockery; \
	echo ""; \
	echo "========================================"; \
	echo "Running Go tests"; \
	echo "========================================"; \
	$(TEST_COMPOSE) run --rm -T test-runner go test ./... -cover


# ============================================================
# Cleanup
# ============================================================

test-clean:
	$(TEST_COMPOSE) down -v --remove-orphans


# ============================================================
# Default
# ============================================================

all: test
