DB_USER      := postgres
DB_PASS      := postgres
DB_NAME      := myapp
DB_HOST      := localhost
DB_PORT      := 5432
HTTP_PORT    ?= 8080
GRPC_PORT    ?= :9091

SCHEMA_FILE  := data/schema.sql
ORM_DIR      := src/internal/repository/orm
PROTO_SRC    := proto/audit_request.proto
PROTO_OUT    := src

COMPOSE := docker compose -f compose.yaml
TEST_COMPOSE := docker compose -f docker-compose.test.yml -p auditor-test

TOOLS_IMAGE  ?= auditor-tools
TOOLS_MOUNT  := -v "$(CURDIR)":/workspace -w /workspace
TOOLS_RUN    := docker run --rm --platform linux/amd64 $(TOOLS_MOUNT) $(TOOLS_IMAGE)
DOCKER_BUILD_PROGRESS ?= plain

MOCKERY_ARGS ?=

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

## Start PostgreSQL, Kafka, load the database schema if missing, and run Auditor
run:
	$(COMPOSE) up -d postgres kafka
	@until $(COMPOSE) exec -T postgres pg_isready -U $(DB_USER) -d $(DB_NAME) >/dev/null 2>&1; do sleep 1; done
	@until $(COMPOSE) exec -T kafka kafka-topics --bootstrap-server localhost:29092 --list >/dev/null 2>&1; do sleep 2; done
	@if ! $(COMPOSE) exec -T postgres psql -U $(DB_USER) -d $(DB_NAME) -tAc "SELECT to_regclass('public.audit_log') IS NOT NULL" | grep -qx t; then \
		echo "Loading Auditor database schema..."; \
		$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) < $(SCHEMA_FILE); \
	fi
	HOST=localhost USER=$(DB_USER) PASSWORD=$(DB_PASS) DB_NAME=$(DB_NAME) SSLMODE=disable GRPC_PORT=$(GRPC_PORT) HTTP_PORT=$(HTTP_PORT) KAFKABROKERS=localhost:9092 HANDLER_IP=localhost:9092 go run ./src/serverd

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

mockery: tools-image
	$(TOOLS_RUN) mockery $(MOCKERY_ARGS)

proto:
	mkdir -p $(PROTO_OUT)
	protoc \
		--go_out=$(PROTO_OUT) \
		--go_opt=module=auditor/src \
		--go-grpc_out=$(PROTO_OUT) \
		--go-grpc_opt=module=auditor/src \
		$(PROTO_SRC)


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
	$(MAKE) generate-orm; \
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
