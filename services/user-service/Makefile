MVN ?= mvn
DOCKER ?= docker
CURL ?= curl
IMAGE ?= user-service:local

API_URL ?= http://localhost:8081
USER_ID ?=
FIRST_NAME ?=
LAST_NAME ?=
EMAIL ?=
ROLE ?= AGENT
OIDC_ISSUER_URI ?=
COGNITO_USER_POOL_ID ?=
COGNITO_APP_CLIENT_ID ?=
AWS_REGION ?=
ROOT_ADMIN_EMAIL ?=
AUTH_ARGS = $(if $(strip $(TOKEN)),-H "Authorization: Bearer $(TOKEN)",)
LOCAL_COMPOSE = $(DOCKER) compose -f docker-compose.local.yml

.PHONY: help test package clean image local-db-up local-db-down local-db-reset local-security-config run run-local down health create-user get-user update-user disable-user reset-password

## Show available build and API commands
help:
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "\033[36m%-18s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

## Run user-service unit and Testcontainers tests (Docker must be available)
test: ## Run the Maven test suite
	$(MVN) -f pom.xml test

## Build the executable Spring Boot JAR
package: ## Package the service
	$(MVN) -f pom.xml package -DskipTests

## Remove Maven build outputs
clean: ## Clean the Maven build
	$(MVN) -f pom.xml clean

## Build the container image from the repository root context
image: ## Build the user-service Docker image
	$(DOCKER) build -f Dockerfile -t $(IMAGE) ../..

## Start the local PostgreSQL database used by the local profile
local-db-up: ## Start the local users_db container on port 5434
	$(LOCAL_COMPOSE) up -d postgres

## Stop local PostgreSQL and keep its volume
local-db-down: ## Stop the local database without deleting its data
	$(LOCAL_COMPOSE) down --remove-orphans

## Stop all user-service local Compose containers and networks, keeping database data
down: local-db-down

## Stop local PostgreSQL and delete its volume/data
local-db-reset: ## Reset the local database and delete its volume
	$(LOCAL_COMPOSE) down -v

## Start the local API with Cognito authentication and authorization enabled
run: local-security-config local-db-up ## Start the local database and run on localhost:8081 with Cognito security
	OIDC_ISSUER_URI="$(OIDC_ISSUER_URI)" COGNITO_USER_POOL_ID="$(COGNITO_USER_POOL_ID)" COGNITO_APP_CLIENT_ID="$(COGNITO_APP_CLIENT_ID)" AWS_REGION="$(AWS_REGION)" ROOT_ADMIN_EMAIL="$(ROOT_ADMIN_EMAIL)" SPRING_PROFILES_ACTIVE=local $(MVN) -f pom.xml spring-boot:run

## Require Cognito configuration before starting the local application
local-security-config:
	@test -n "$(OIDC_ISSUER_URI)" || (echo "Set OIDC_ISSUER_URI to the Cognito user-pool issuer URL"; exit 1)
	@test -n "$(COGNITO_USER_POOL_ID)" || (echo "Set COGNITO_USER_POOL_ID"; exit 1)
	@test -n "$(COGNITO_APP_CLIENT_ID)" || (echo "Set COGNITO_APP_CLIENT_ID"; exit 1)
	@test -n "$(AWS_REGION)" || (echo "Set AWS_REGION"; exit 1)
	@test -n "$(ROOT_ADMIN_EMAIL)" || (echo "Set ROOT_ADMIN_EMAIL"; exit 1)

## Alias for run
run-local: run

## Check the deployed service health endpoint (does not require a token)
health: ## Call /actuator/health; set API_URL to the deployed service URL
	$(CURL) --fail-with-body --silent --show-error "$(API_URL)/actuator/health"

## Create an ADMIN/AGENT through the authenticated API
create-user: ## Create a user; set API_URL, TOKEN, FIRST_NAME, LAST_NAME, and EMAIL
	@test -n "$(TOKEN)" || (echo "Set TOKEN to a Cognito access token"; exit 1)
	@test -n "$(FIRST_NAME)" -a -n "$(LAST_NAME)" -a -n "$(EMAIL)" || (echo "Set FIRST_NAME, LAST_NAME, and EMAIL"; exit 1)
	$(CURL) --fail-with-body --silent --show-error -X POST "$(API_URL)/api/users" \
		$(AUTH_ARGS) -H "Content-Type: application/json" \
		-d '{"firstName":"$(FIRST_NAME)","lastName":"$(LAST_NAME)","email":"$(EMAIL)","role":"$(ROLE)"}'

## Fetch a user through the authenticated API
get-user: ## Fetch a user; set API_URL, TOKEN, and USER_ID
	@test -n "$(TOKEN)" || (echo "Set TOKEN to a Cognito access token"; exit 1)
	@test -n "$(API_URL)" -a -n "$(USER_ID)" || (echo "Set API_URL and USER_ID"; exit 1)
	$(CURL) --fail-with-body --silent --show-error "$(API_URL)/api/users/$(USER_ID)" \
		$(AUTH_ARGS)

## Update a user's first and last names
update-user: ## Update a user; set API_URL, TOKEN, USER_ID, FIRST_NAME, and LAST_NAME
	@test -n "$(TOKEN)" || (echo "Set TOKEN to a Cognito access token"; exit 1)
	@test -n "$(API_URL)" -a -n "$(USER_ID)" -a -n "$(FIRST_NAME)" -a -n "$(LAST_NAME)" || (echo "Set API_URL, USER_ID, FIRST_NAME, and LAST_NAME"; exit 1)
	$(CURL) --fail-with-body --silent --show-error -X PUT "$(API_URL)/api/users/$(USER_ID)" \
		$(AUTH_ARGS) -H "Content-Type: application/json" \
		-d '{"firstName":"$(FIRST_NAME)","lastName":"$(LAST_NAME)"}'

## Disable a user
disable-user: ## Disable a user; set API_URL, TOKEN, and USER_ID
	@test -n "$(TOKEN)" || (echo "Set TOKEN to a Cognito access token"; exit 1)
	@test -n "$(API_URL)" -a -n "$(USER_ID)" || (echo "Set API_URL and USER_ID"; exit 1)
	$(CURL) --fail-with-body --silent --show-error -X PATCH "$(API_URL)/api/users/$(USER_ID)/disable" \
		$(AUTH_ARGS)

## Start the Cognito password reset flow for a user
reset-password: ## Reset a user's password; set API_URL, TOKEN, and USER_ID
	@test -n "$(TOKEN)" || (echo "Set TOKEN to a Cognito access token"; exit 1)
	@test -n "$(API_URL)" -a -n "$(USER_ID)" || (echo "Set API_URL and USER_ID"; exit 1)
	$(CURL) --fail-with-body --silent --show-error -X POST "$(API_URL)/api/users/$(USER_ID)/reset-password" \
		$(AUTH_ARGS)
