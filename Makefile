DB_USER     := postgres
DB_PASS     := postgres
DB_NAME     := myapp
DB_HOST     := localhost
DB_PORT     := 5432
SCHEMA_FILE := services/client-profile-management-service/data/V1_init.sql
ORM_DIR     := services/client-profile-management-service/src/internal/repository/orm
TEST_COMPOSE := docker compose -f docker-compose-test.yaml -p client-profile-management-test
TOOLS_IMAGE ?= client-profile-management-tools
TOOLS_MOUNT := -v "$(PWD)":/workspace -w /workspace/services/client-profile-management-service
TOOLS_RUN := docker run --rm $(TOOLS_MOUNT) --network host $(TOOLS_IMAGE)
MOCKERY_ARGS ?=

.PHONY: db-up db-down db-logs db-reset load-schema psql tools-image sqlboiler mockery test all

## Start Postgres and wait until it's ready
db-up:
	docker compose up -d
	@echo "Waiting for postgres to be healthy..."
	@until docker compose exec -T postgres pg_isready -U $(DB_USER) >/dev/null 2>&1; do sleep 1; done
	@echo "Postgres is up."

## Stop and remove the container (keeps the volume)
db-down:
	docker compose down

## Stop, remove container AND wipe the data volume
db-reset:
	docker compose down -v

## Tail postgres logs
db-logs:
	docker compose logs -f postgres

## Load your .sql script into the running database
load-schema:
	PGPASSWORD=$(DB_PASS) psql -h $(DB_HOST) -p $(DB_PORT) -U $(DB_USER) -d $(DB_NAME) -f $(SCHEMA_FILE)

## Open a psql shell into the running database
psql:
	PGPASSWORD=$(DB_PASS) psql -h $(DB_HOST) -p $(DB_PORT) -U $(DB_USER) -d $(DB_NAME)

## Build the generator image without installing tools locally
tools-image:
	docker build --target tools -t $(TOOLS_IMAGE) services/client-profile-management-service

## Run SQLBoiler against a disposable Docker Postgres database
sqlboiler:
	@$(TEST_COMPOSE) down -v --remove-orphans >/dev/null 2>&1 || true
	@trap '$(TEST_COMPOSE) down -v --remove-orphans' EXIT; \
	$(TEST_COMPOSE) up -d postgres; \
	until $(TEST_COMPOSE) exec -T postgres pg_isready -U $(DB_USER) -d $(DB_NAME) >/dev/null 2>&1; do sleep 1; done; \
	$(TEST_COMPOSE) exec -T postgres psql -U $(DB_USER) -d $(DB_NAME) < $(SCHEMA_FILE); \
	$(TEST_COMPOSE) run --rm tools sqlboiler -c sqlboiler.docker.toml psql -o src/internal/repository/orm --wipe; \
	for f in $(ORM_DIR)/*.go; do \
		case "$$f" in \
			*/mocks_*) ;; \
			*) mv "$$f" "$(ORM_DIR)/mocks_$$(basename "$$f")" ;; \
		esac; \
	done

## Generate mocks for every interface recursively
mockery: tools-image
	$(TOOLS_RUN) mockery $(MOCKERY_ARGS)

## Run the full test loop in disposable Docker containers
test:
	@$(TEST_COMPOSE) down -v --remove-orphans >/dev/null 2>&1 || true
	@trap '$(TEST_COMPOSE) down -v --remove-orphans' EXIT; \
	$(TEST_COMPOSE) up -d postgres; \
	until $(TEST_COMPOSE) exec -T postgres pg_isready -U $(DB_USER) -d $(DB_NAME) >/dev/null 2>&1; do sleep 1; done; \
	$(TEST_COMPOSE) exec -T postgres psql -U $(DB_USER) -d $(DB_NAME) < $(SCHEMA_FILE); \
	$(TEST_COMPOSE) run --rm tools sqlboiler -c sqlboiler.docker.toml psql -o src/internal/repository/orm --wipe; \
	for f in $(ORM_DIR)/*.go; do \
		case "$$f" in \
			*/mocks_*) ;; \
			*) mv "$$f" "$(ORM_DIR)/mocks_$$(basename "$$f")" ;; \
		esac; \
	done; \
	$(TEST_COMPOSE) run --rm test-runner go test ./...

all: test
