DB_USER     := postgres
DB_PASS     := postgres
DB_NAME     := myapp
DB_HOST     := localhost
DB_PORT     := 5432
SCHEMA_FILE := data/V1_init.sql
ORM_DIR     := src/internal/repository/orm
COMPOSE := docker compose -f docker-compose.yaml
TEST_COMPOSE := docker compose -f docker-compose-test.yaml -p client-profile-management-test
TOOLS_IMAGE ?= client-profile-management-tools
DOCKER_BUILD_PROGRESS ?= plain
TOOLS_MOUNT := -v "$(CURDIR)":/workspace -w /workspace
TOOLS_RUN := docker run --rm --platform linux/amd64 $(TOOLS_MOUNT) --network host $(TOOLS_IMAGE)
MOCKERY_ARGS ?=
COGNITO_ISSUER_URI ?=
COGNITO_APP_CLIENT_ID ?=
COGNITO_API_AUDIENCE ?=
AWS_REGION ?=
AWS_ENDPOINT_URL ?=
AUDIT_QUEUE_URL ?=
# Client data encryption (ADR-0030). The defaults match localstack/init-client-keys.sh.
CLIENT_KEYS_TABLE ?= client-keys
CLIENT_DATA_KEY_ID ?= alias/client-data
CLIENT_INDEX_KEY_ID ?= alias/client-blind-index
AWS_ENDPOINT_URL_KMS ?= http://localhost:4567
AWS_ENDPOINT_URL_DYNAMODB ?= http://localhost:4567
ACCOUNT_SERVICE_BASE_URL ?= http://localhost:8083
DB_SSLMODE ?= disable
DB_SSLROOTCERT ?=
APP_ENV ?= local
APP_TLS_CERT_FILE ?=
APP_TLS_KEY_FILE ?=

.PHONY: db-up db-down db-logs db-reset load-schema psql check-security-config run down tools-image sqlboiler mockery test all

## Start Postgres and wait until it's ready
db-up:
	$(COMPOSE) up -d
	@echo "Waiting for postgres to be healthy..."
	@until docker compose exec -T postgres pg_isready -U $(DB_USER) >/dev/null 2>&1; do sleep 1; done
	@echo "Postgres is up."
	@echo "Waiting for the LocalStack encryption keys..."
	@until $(COMPOSE) exec -T localstack awslocal kms describe-key --key-id alias/client-blind-index >/dev/null 2>&1; do sleep 1; done
	@echo "LocalStack keys are ready."

## Stop and remove the container (keeps the volume)
db-down:
	$(COMPOSE) down --remove-orphans
	$(TEST_COMPOSE) down --remove-orphans

## Stop all client-service Compose containers and networks, keeping database volumes
down: db-down

## Stop, remove container AND wipe the data volume
db-reset:
	$(COMPOSE) down -v

## Tail postgres logs
db-logs:
	$(COMPOSE) logs -f postgres

## Load your .sql script into the running database
load-schema:
	PGPASSWORD=$(DB_PASS) psql -h $(DB_HOST) -p $(DB_PORT) -U $(DB_USER) -d $(DB_NAME) -f $(SCHEMA_FILE)

## Verify the required Cognito issuer and app-client settings before starting
check-security-config:
	@test -n "$(COGNITO_ISSUER_URI)" || (echo "Set COGNITO_ISSUER_URI to the Cognito user-pool issuer URL"; exit 1)
	@test -n "$(COGNITO_APP_CLIENT_ID)" || (echo "Set COGNITO_APP_CLIENT_ID to the Cognito app-client ID"; exit 1)
	@test -n "$(AWS_REGION)" || (echo "Set AWS_REGION for the Auditor SQS queue"; exit 1)
	@test -n "$(AUDIT_QUEUE_URL)" || (echo "Set AUDIT_QUEUE_URL to the Auditor audit-insert queue URL"; exit 1)
	@test -n "$(ACCOUNT_SERVICE_BASE_URL)" || (echo "Set ACCOUNT_SERVICE_BASE_URL to the account-service URL, which is asked before a client is deleted"; exit 1)
	@test -n "$(CLIENT_KEYS_TABLE)" -a -n "$(CLIENT_DATA_KEY_ID)" -a -n "$(CLIENT_INDEX_KEY_ID)" || (echo "Set CLIENT_KEYS_TABLE, CLIENT_DATA_KEY_ID and CLIENT_INDEX_KEY_ID for client data encryption"; exit 1)
	@if [ "$(APP_ENV)" = production ]; then \
		test "$(DB_SSLMODE)" = verify-full -a -n "$(DB_SSLROOTCERT)" || (echo "Production requires DB_SSLMODE=verify-full and DB_SSLROOTCERT"; exit 1); \
		test -n "$(APP_TLS_CERT_FILE)" -a -n "$(APP_TLS_KEY_FILE)" || (echo "Production requires APP_TLS_CERT_FILE and APP_TLS_KEY_FILE"; exit 1); \
	fi

## Start PostgreSQL, apply schema changes, and run the Cognito-protected Gin API
run: check-security-config db-up
	@if ! $(COMPOSE) exec -T postgres psql -U $(DB_USER) -d $(DB_NAME) -tAc "SELECT to_regclass('public.clients') IS NOT NULL" | grep -qx t; then \
		echo "Loading client schema..."; \
		$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) < $(SCHEMA_FILE); \
	fi
	$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) < data/V2_agent_subject.sql
	DB_HOST=$(DB_HOST) DB_PORT=$(DB_PORT) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASS) DB_NAME=$(DB_NAME) DB_SSLMODE=$(DB_SSLMODE) DB_SSLROOTCERT="$(DB_SSLROOTCERT)" APP_ENV=$(APP_ENV) APP_TLS_CERT_FILE="$(APP_TLS_CERT_FILE)" APP_TLS_KEY_FILE="$(APP_TLS_KEY_FILE)" COGNITO_ISSUER_URI="$(COGNITO_ISSUER_URI)" COGNITO_APP_CLIENT_ID="$(COGNITO_APP_CLIENT_ID)" COGNITO_API_AUDIENCE="$(COGNITO_API_AUDIENCE)" AWS_REGION="$(AWS_REGION)" AWS_ENDPOINT_URL="$(AWS_ENDPOINT_URL)" AUDIT_QUEUE_URL="$(AUDIT_QUEUE_URL)" ACCOUNT_SERVICE_BASE_URL="$(ACCOUNT_SERVICE_BASE_URL)" CLIENT_KEYS_TABLE="$(CLIENT_KEYS_TABLE)" CLIENT_DATA_KEY_ID="$(CLIENT_DATA_KEY_ID)" CLIENT_INDEX_KEY_ID="$(CLIENT_INDEX_KEY_ID)" AWS_ENDPOINT_URL_KMS="$(AWS_ENDPOINT_URL_KMS)" AWS_ENDPOINT_URL_DYNAMODB="$(AWS_ENDPOINT_URL_DYNAMODB)" go run ./src/serverd

## Open a psql shell into the running database
psql:
	PGPASSWORD=$(DB_PASS) psql -h $(DB_HOST) -p $(DB_PORT) -U $(DB_USER) -d $(DB_NAME)

## Build the generator image without installing tools locally
tools-image:
	docker build --progress=$(DOCKER_BUILD_PROGRESS) --platform linux/amd64 --target tools -t $(TOOLS_IMAGE) .

## Run SQLBoiler against a disposable Docker Postgres database
sqlboiler:
	@$(TEST_COMPOSE) down -v --remove-orphans >/dev/null 2>&1 || true
	@set -e; trap '$(TEST_COMPOSE) down -v --remove-orphans' EXIT; \
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

## Run the full test loop in disposable Docker containers. It generates the
## ORM and the mocks (both git-ignored), so it works from a clean checkout, as
## in CI. Old mocks are removed first: one left over from a removed method
## would stop mockery loading its package.
test:
	@$(TEST_COMPOSE) down -v --remove-orphans >/dev/null 2>&1 || true
	@set -e; trap '$(TEST_COMPOSE) down -v --remove-orphans' EXIT; \
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
	find src -name mocks.go -delete; \
	$(TEST_COMPOSE) run --rm tools mockery; \
	$(TEST_COMPOSE) run --rm test-runner go test ./... -cover

all: test
