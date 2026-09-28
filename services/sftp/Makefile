COMPOSE ?= docker compose
COMPOSE_FILE ?= compose.yaml
SFTP_KEY_FILE ?= $(shell sed -n 's/^SFTP_KEY_FILE=//p' .env)
SFTP_HOST_KEY_FINGERPRINT ?= $(shell sed -n 's/^SFTP_HOST_KEY_FINGERPRINT=//p' .env)

.PHONY: test compose-check build up down tools-build deps db-up sqlboiler mocks generate poller

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
		go-tools go test ./src/internal/repository ./src/internal/models ./src/cronjob ./src/cronjob/poller

build:
	$(COMPOSE) -f $(COMPOSE_FILE) build

up:
	$(COMPOSE) -f $(COMPOSE_FILE) up --build -d

down:
	$(COMPOSE) -f $(COMPOSE_FILE) down

tools-build:
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools build go-tools

db-up:
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --wait db

sqlboiler: db-up tools-build
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools run --rm go-tools sqlboiler psql --config sqlboiler.toml --output src/internal/repository/orm --pkgname orm

mocks: tools-build
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools run --rm go-tools mockery --config .mockery.yaml

generate: sqlboiler mocks

poller: tools-build
	@test -n "$(SFTP_KEY_FILE)" || (echo "set SFTP_KEY_FILE to the path of the SFTP client private key" >&2; exit 1)
	@test -n "$(SFTP_HOST_KEY_FINGERPRINT)" || (echo "set SFTP_HOST_KEY_FINGERPRINT to the server's SHA256 fingerprint" >&2; exit 1)
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --wait db sftp
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools run --rm \
		-v "$(SFTP_KEY_FILE):/run/secrets/sftp-client-key:ro" \
		-e SFTP_PRIVATE_KEY_PATH=/run/secrets/sftp-client-key \
		-e SFTP_HOST_KEY_FINGERPRINT="$(SFTP_HOST_KEY_FINGERPRINT)" \
		go-tools go run ./src/cronjob
