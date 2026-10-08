COMPOSE ?= docker compose
COMPOSE_FILE ?= compose.yaml
SFTP_KEY_FILE ?= $(shell sed -n 's/^SFTP_KEY_FILE=//p' .env)
SFTP_KEY_PATH := $(abspath $(SFTP_KEY_FILE))
SFTP_HOST_KEY_FINGERPRINT ?= $(shell sed -n 's/^SFTP_HOST_KEY_FINGERPRINT=//p' .env)

.PHONY: test compose-check build up down tools-build deps db-up db-attach-crm sqlboiler mocks generate poller poller-once

compose-check:
	$(COMPOSE) -f $(COMPOSE_FILE) config --quiet

deps: tools-build
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools run --rm go-tools go mod tidy

test: deps
	@set -e; \
	$(COMPOSE) -f $(COMPOSE_FILE) --profile test up -d --wait test-db; \
	trap '$(COMPOSE) -f $(COMPOSE_FILE) --profile test rm -sf test-db >/dev/null 2>&1 || true' EXIT; \
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools --profile test run --rm \
		-e DB_HOST=test-db -e DB_PORT=5432 -e DB_USER=crm -e DB_PASSWORD=crm -e DB_NAME=crm \
		go-tools go test -v ./src/internal/repository ./src/internal/models ./src/cronjob ./src/cronjob/poller

build:
	$(COMPOSE) --progress plain -f $(COMPOSE_FILE) build

up: db-attach-crm
	$(COMPOSE) -f $(COMPOSE_FILE) up --build -d

down:
	$(COMPOSE) -f $(COMPOSE_FILE) down

tools-build:
	$(COMPOSE) --progress plain -f $(COMPOSE_FILE) --profile tools build go-tools

db-up:
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --wait db

# Apply the CRM schema to an existing SFTP database volume. A new volume gets
# these scripts from the ordered PostgreSQL initialization mounts in compose.yaml.
db-attach-crm: db-up
	@if ! $(COMPOSE) -f $(COMPOSE_FILE) exec -T db sh -c 'psql -U "$$POSTGRES_USER" -d "$$POSTGRES_DB" -tAc "SELECT to_regclass('"'"'public.clients'"'"') IS NOT NULL"' | grep -qx t; then \
		$(COMPOSE) -f $(COMPOSE_FILE) exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$$POSTGRES_USER" -d "$$POSTGRES_DB"' < ../client-profile-management-service/data/V1_init.sql; \
	fi
	$(COMPOSE) -f $(COMPOSE_FILE) exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$$POSTGRES_USER" -d "$$POSTGRES_DB"' < ../client-profile-management-service/data/V2_agent_subject.sql
	$(COMPOSE) -f $(COMPOSE_FILE) exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$$POSTGRES_USER" -d "$$POSTGRES_DB"' < receiver/data/V2_account_link.sql

sqlboiler: db-up tools-build
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools run --rm go-tools sqlboiler psql --config sqlboiler.toml --output src/internal/repository/orm --pkgname orm

mocks: tools-build
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools run --rm go-tools mockery --config .mockery.yaml

generate: sqlboiler mocks

poller: tools-build
	@test -n "$(SFTP_KEY_FILE)" || (echo "set SFTP_KEY_FILE to the path of the SFTP client private key" >&2; exit 1)
	@test -f "$(SFTP_KEY_PATH)" || (echo "SFTP_KEY_FILE does not point to a readable key file: $(SFTP_KEY_PATH)" >&2; exit 1)
	@test -n "$(SFTP_HOST_KEY_FINGERPRINT)" || (echo "set SFTP_HOST_KEY_FINGERPRINT to the server's SHA256 fingerprint" >&2; exit 1)
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --wait db sftp
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools run --rm \
		-v "$(SFTP_KEY_PATH):/run/secrets/sftp-client-key:ro" \
		-e SFTP_PRIVATE_KEY_PATH=/run/secrets/sftp-client-key \
		-e SFTP_HOST_KEY_FINGERPRINT="$(SFTP_HOST_KEY_FINGERPRINT)" \
		go-tools go run ./src/cronjob

poller-once: tools-build
	@test -n "$(SFTP_KEY_FILE)" || (echo "set SFTP_KEY_FILE to the path of the SFTP client private key" >&2; exit 1)
	@test -f "$(SFTP_KEY_PATH)" || (echo "SFTP_KEY_FILE does not point to a readable key file: $(SFTP_KEY_PATH)" >&2; exit 1)
	@test -n "$(SFTP_HOST_KEY_FINGERPRINT)" || (echo "set SFTP_HOST_KEY_FINGERPRINT to the server's SHA256 fingerprint" >&2; exit 1)
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --build --wait db sftp producer
	$(COMPOSE) -f $(COMPOSE_FILE) exec -T sftp sh -c 'until ls /home/reportuser/reports/report-*.json >/dev/null 2>&1; do sleep 1; done'
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools run --rm \
		-v "$(SFTP_KEY_PATH):/run/secrets/sftp-client-key:ro" \
		-e SFTP_PRIVATE_KEY_PATH=/run/secrets/sftp-client-key \
		-e SFTP_HOST_KEY_FINGERPRINT="$(SFTP_HOST_KEY_FINGERPRINT)" \
		go-tools go run ./src/cronjob -once
