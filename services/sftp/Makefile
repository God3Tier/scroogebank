COMPOSE ?= docker compose
COMPOSE_FILE ?= compose.yaml
SFTP_KEY_FILE ?= $(shell sed -n 's/^SFTP_KEY_FILE=//p' .env)
SFTP_KEY_PATH := $(abspath $(SFTP_KEY_FILE))
SFTP_HOST_KEY_FINGERPRINT ?= $(shell sed -n 's/^SFTP_HOST_KEY_FINGERPRINT=//p' .env)

.PHONY: test producer-test compose-check build up down tools-build deps db-up sqlboiler mocks generate poller poller-once fault lambda

compose-check:
	$(COMPOSE) -f $(COMPOSE_FILE) config --quiet

deps: tools-build
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools run --rm go-tools go mod tidy

## Run the receiver's tests against a disposable database. It generates the ORM
## and the mocks (both git-ignored) first, so it works from a clean checkout, as
## in CI. It does not run go mod tidy: before the mocks exist, tidy would drop
## the testify/mock dependencies they need. PSQL_HOST points SQLBoiler at the
## test database instead of sqlboiler.toml's host. The producer's tests run first.
test: tools-build producer-test
	@set -e; \
	$(COMPOSE) -f $(COMPOSE_FILE) --profile test up -d --wait test-db; \
	trap '$(COMPOSE) -f $(COMPOSE_FILE) --profile test rm -sf test-db >/dev/null 2>&1 || true' EXIT; \
	run="$(COMPOSE) -f $(COMPOSE_FILE) --profile tools --profile test run --rm \
		-e PSQL_HOST=test-db -e DB_HOST=test-db -e DB_PORT=5432 -e DB_USER=crm -e DB_PASSWORD=crm -e DB_NAME=transactions \
		go-tools"; \
	$$run sqlboiler psql --config sqlboiler.toml --output src/internal/repository/orm --pkgname orm --wipe; \
	$$run sh -c 'find src -name mocks.go -delete && mockery --config .mockery.yaml'; \
	$$run go test -v ./...

build:
	$(COMPOSE) --progress plain -f $(COMPOSE_FILE) build

up:
	$(COMPOSE) -f $(COMPOSE_FILE) up --build -d

down:
	$(COMPOSE) -f $(COMPOSE_FILE) down

tools-build:
	$(COMPOSE) --progress plain -f $(COMPOSE_FILE) --profile tools build go-tools

db-up:
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --wait db

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

## Build the transaction-ingest Lambda function's zip, receiver/dist/transaction-ingest.zip
## (ADR-0024 decision 7): the poller as bootstrap for provided.al2023 on arm64,
## and the RDS CA bundle that DB_SSLROOTCERT names. It is zipped in the
## container so bootstrap keeps its execute permission. Set the crm stack's
## ingest_lambda_zip to that file.
RDS_CA_URL ?= https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem

lambda: tools-build
	$(COMPOSE) -f $(COMPOSE_FILE) --profile tools run --rm --no-deps \
		-e GOOS=linux -e GOARCH=arm64 -e CGO_ENABLED=0 \
		go-tools sh -c 'set -e; rm -rf dist && mkdir -p dist; \
			go build -tags lambda.norpc -trimpath -o dist/bootstrap ./src/cronjob; \
			curl -fsSL -o dist/rds-ca.pem "$(RDS_CA_URL)"; \
			cd dist && zip -q -X transaction-ingest.zip bootstrap rds-ca.pem'

## Run the producer's tests: they need only Go, so they run in the image's
## build stage, with no volume to mount.
producer-test:
	docker build --progress plain --target test producer

## Publish one fault file for the demo (ADR-0024 decision 1), for example
## make fault KIND=corrupt. The kinds are listed in README.md.
fault:
	@test -n "$(KIND)" || (echo "set KIND to one of: corrupt, partial, all-invalid, unsupported, resend" >&2; exit 1)
	$(COMPOSE) -f $(COMPOSE_FILE) run --rm --build producer -fault $(KIND)
