DB_USER      := postgres
DB_PASS      := postgres
DB_NAME      := myapp
DB_HOST      := localhost
DB_PORT      := 5432

SCHEMA_FILE  := data/audit_log_table.sql
ORM_DIR      := services/auditor/src/internal/repository/orm
PROTO_SRC    := services/auditor/proto/audit_request.proto
PROTO_OUT    := services/auditor/src

TEST_COMPOSE := docker compose -f docker-compose.test.yml -p auditor-test

TOOLS_IMAGE  ?= auditor-tools
TOOLS_MOUNT  := -v "$(PWD)":/workspace -w /workspace/services/auditor
TOOLS_RUN    := docker run --rm $(TOOLS_MOUNT) $(TOOLS_IMAGE)

MOCKERY_ARGS ?=

.PHONY: \
	db-up db-down db-logs db-reset load-schema psql \
	tools-image mockery proto \
	test-db-up test-db-down generate-orm sqlboiler \
	test test-clean all


# ============================================================
# Local development PostgreSQL
# docker-compose.yml
# ============================================================

db-up:
	docker compose up -d postgres
	@echo "Waiting for PostgreSQL..."
	@until docker compose exec -T postgres \
		pg_isready -U $(DB_USER) -d $(DB_NAME) >/dev/null 2>&1; do \
		sleep 1; \
	done
	@echo "PostgreSQL is ready."

db-down:
	docker compose down

db-reset:
	docker compose down -v

db-logs:
	docker compose logs -f postgres

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
	docker build \
		--target tools \
		-t $(TOOLS_IMAGE) \
		services/auditor

mockery: tools-image
	$(TOOLS_RUN) mockery $(MOCKERY_ARGS)

proto:
	mkdir -p $(PROTO_OUT)
	protoc \
		--go_out=$(PROTO_OUT) \
		--go_opt=paths=source_relative \
		--go-grpc_out=$(PROTO_OUT) \
		--go-grpc_opt=paths=source_relative \
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
		< services/auditor/$(SCHEMA_FILE)

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

	@echo "Renaming generated ORM files..."

	@for f in $(ORM_DIR)/*.go; do \
		if [ -f "$$f" ]; then \
			base=$$(basename "$$f"); \
			case "$$base" in \
				mocks_*) ;; \
				*) mv "$$f" "$(ORM_DIR)/mocks_$$base" ;; \
			esac \
		fi \
	done

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
