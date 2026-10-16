# Account-service split: follow-ups

Last updated: 2026-10-08 · Branch: `task/client-profile-management/account-service-split` (merged to `dev` as PR #65). Soft delete: `task/client-profile-management/soft-delete` (merged as PR #66). Separate databases: `task/transaction-integration/separate-transactions-db` (from `origin/dev` at `e67c28b`, uncommitted)

The split itself is done: `services/account-service` owns accounts, `client-profile-management-service` owns clients, and the accounts-to-clients foreign key is replaced by API checks ([ADR-0035](docs/adr/ADR-0035-account-ownership-across-services.md)). This file lists what the split did **not** do, in the order it should be picked up.

## 1. Before merging this branch

| # | Item | Why it blocks |
|---|---|---|
| 1.1 | **Unit and database tests pass; the end-to-end checks have not been run.** See "Test results" below. The end-to-end checks need real Cognito tokens and all three services running. | Ownership across services is only proven piecewise until they run. |
| 1.2 | ~~Accept ADR-0017 and ADR-0035.~~ **Done 2026-10-08.** Both are Accepted, so under `project_plan.md` §7.4 any further change to them needs a new superseding ADR. | — |
| 1.5 | **Reconcile with ADR-0006 (Accepted, Joseph Yau).** ADR-0035 supersedes its decision 4 (the ownership check now goes to account-service), and the index says so. ADR-0006's `Superseded by:` field now names ADR-0035 for decision 4 only (approved by Joseph). Left for Joseph: deciding how ADR-0006 decision 1 (one shared CRM database) stands against ADR-0017 (one database per service). Both are Accepted, so they conflict as written. The likely resolution is a Feature 3 ADR superseding decisions 1–2, alongside item 3 below. | Two Accepted ADRs contradict each other until then. |
| 1.3 | **No CI builds either Go service.** `.github/workflows/ci.yml` runs `mvn -B verify` only. | A broken Go change merges green. Run 1.1 by hand until a Go job exists. |
| 1.4 | **Agree the split with Joseph Yau**, Feature 2's primary developer. Logged as `F2-SPLIT-01` in `docs/feature-plans/feature-2_plan.md`. | It moves his code and changes his API. |

### Test results (2026-10-08, Go 1.27.1, Docker Desktop)

| Service | Build and `go vet` | Tests |
|---|---|---|
| account-service | Pass | Every new and moved test passes, including the database-backed `RequireAccountOwner` and account-list tests. Two failures exist on `dev` too (below) |
| client-profile-management-service | Pass | Every changed package passes. The same two failures exist on `dev` too |
| user-service | Pass | 24 of 24, including Testcontainers (run in `maven:3-eclipse-temurin-25`, because Maven isn't installed) |

**Failures that are not from this branch:**

- **`TestAuditLogger_DoesNotSendWithoutAuthenticatedAgentID`** (`serverd/sqs`, both services). It removes the agent ID and then asserts one send attempt; the middleware correctly sends none. The assertion contradicts the test's name. It fails identically in client-profile-management-service's unchanged copy.
- **`TestGetAccount/returns_not_found_for_missing_account`** (account repository). It looks up account `…0202`, but its fixture `account_empty.sql` deletes `…0299`, so the `…0202` row inserted by the case before it is still there. Confirmed by running the untouched test on `origin/dev` (`3a08ac1`) in a temporary worktree: same failure.
- **The `orm` package** in both services. These are SQLBoiler's generated tests, which shell out to `createdb`/`dropdb`; the PostgreSQL client tools aren't installed here. Not code from either branch.

Both real failures are one-line fixes, left for a decision because one lives in Feature 2's code.

**How it was run**, since `make` isn't available in Git Bash. For each Go service: start its test Postgres with `docker compose -f docker-compose-test.yaml up -d postgres`, load `data/V1_init.sql`, generate models with `sqlboiler -c sqlboiler.toml psql -o src/internal/repository/orm --wipe` (with `PSQL_PORT` set to the test port: 5435 for account-service, 5433 for client-profile-management-service), run `mockery`, then `go test ./... -cover` with `DB_PORT` set to the same port. sqlboiler v4.19.7 and mockery v3.8.0 were installed with `go install`, matching the Dockerfile.

**Still to run:** the end-to-end checks in the plan. Agent B cannot open, read or delete an account on agent A's client; an admin opening an account on A's client leaves A as the owner; deleting a client with an account returns 409, and 503 with account-service stopped.

Also worth doing: `go mod tidy` in `services/account-service`. Its `go.mod` and `go.sum` were copied whole from client-profile-management-service, so they may list modules it no longer uses.

## 2. ~~Close accounts instead of deleting them~~ Replaced by soft delete

**Implemented 2026-10-08** on `task/client-profile-management/soft-delete` ([ADR-0036](docs/adr/ADR-0036-soft-deletion-of-clients-and-accounts.md), which supersedes ADR-0035 decision 4 in part). The original idea here, setting `status = 'closed'`, was wrong: the requirements Q&A (CPM08, CPM21, CPM25, CPM33, UM19) asks for **soft delete**, which is not the same as closing.

- `DELETE /api/clients/{id}` and `DELETE /api/accounts/{id}` set `deleted_at` and keep the row; `status` is unchanged.
- Every endpoint treats deleted records as missing (404 or left out of lists), for admins too.
- Email, phone and one-account-per-type are unique only among rows that aren't deleted.
- The client-delete check counts accounts that aren't deleted, so closed but undeleted accounts still block it.
- Still open: erasing soft-deleted data and a retention period ([open item 21](docs/architecture/open-items.md#21-erasing-soft-deleted-client-data)); the end-to-end checks.

## 3–4. ~~The receiver's account link; a separate transactions database~~ Done

**Implemented 2026-10-08** on `task/transaction-integration/separate-transactions-db` ([ADR-0037](docs/adr/ADR-0037-transactions-database-and-account-linking.md), agreed with Joseph, superseding ADR-0006 decisions 1–2). Clients, accounts and transactions each have their own database, locally too: ports 5432, 5436 and 5437. The `run-shared-db` and `db-attach-crm` targets are gone.

- The receiver's foreign key and trigger are replaced by one lookup per file to account-service's `POST /internal/accounts/lookup`. Rows get `account_id`, `client_id` and `Matched`; everything else, including deleted accounts, is `Unmatched`.
- The poller authenticates with a Cognito client-credentials token (ADR-0025). account-service accepts only listed app clients with the `crm-accounts/lookup` scope on `/internal`.
- If account-service can't answer, the file waits for the next poll and nothing is saved.
- The producer is unchanged: it still uses `CRM_ACCOUNT_IDS`.

**Still open:**
- **Setup before anyone can run it:** add the Cognito resource server `crm-accounts` (scope `lookup`), the app client `transaction-ingest` with a secret, and a user pool domain. Put the secret in `services/sftp/.env`.
- **End-to-end checks** with real Cognito tokens (ADR-0037, Validation).
- **Not done on purpose:** copying `owner_agent_id` onto transactions. The read path checks ownership through account-service, so it isn't needed yet; the dashboard may want it.
- **If ADR-0024's Lambda replaces the Go poller,** it needs the same lookup, plus a route to account-service other than Service Connect.

## 5. Infrastructure requirements

Infra is built later, so these are requirements, not changes:

- A new ECS service for account-service (Go image; listens on `:8083` by default) and its own `accounts-db`.
- `CLIENT_SERVICE_BASE_URL` on account-service and `ACCOUNT_SERVICE_BASE_URL` on client-profile-management-service. Both are required, and both must be `https://` in production, because they carry the caller's access token.
- user-service: `ACCOUNT_SERVICE_BASE_URL` replaces `CLIENT_PROFILE_BASE_URL`.
- Service Connect from account-service to client-profile-management-service and back, and from user-service to account-service.
- API Gateway: route `/api/accounts/*` to account-service. It went to client-profile-management-service before. user-service still serves `/api/accounts/{id}/transactions`, so that more specific route must win.
- account-service's task role: `sqs:SendMessage` on `audit-insert`, as client-profile-management-service has.
- **Transaction import (ADR-0037):**
  - in Terraform: the Cognito resource server `crm-accounts` with scope `lookup`, and the app client `transaction-ingest` (client-credentials grant, that scope only, with a secret);
  - the secret in Secrets Manager;
  - `COGNITO_SERVICE_CLIENT_IDS` on account-service;
  - a private, HTTPS route from the ingest worker to account-service's `/internal/accounts/lookup`, never through API Gateway.
  - Cognito bills about USD 0.00225 per service token. Tokens are reused for an hour, so under USD 2 a month.

## 6. Elsewhere

- ~~**Dashboard**: account figures move to account-service and scope by `owner_agent_id`.~~ **Done 2026-10-09** on the restarted `feat/business-dashboard`: account-service serves `GET /aggregates/accounts/stats` and `/aggregates/accounts/ids`, scoped by `owner_agent_id` ([ADR-0039](docs/adr/ADR-0039-dashboard-aggregate-endpoints.md), Proposed; contract in `docs/api/dashboard-aggregates.yaml`, which replaces `dashboard-pushdown.yaml`).
- **Duplicated Go code.** account-service carries copies of `security/cognito.go` and `serverd/sqs/function_sender.go`. A fix to either has to go to both services until a shared module exists.
- **Rename** `client-profile-management-service` to `client-service`, as ADR-0017 names it. Deferred to keep this change small. It touches every Go import, the Dockerfile and Makefile, and the sftp and user-service references.
- **Local database volumes from before the split.** client-profile-management-service's volume keeps an unused `accounts` table (`make db-reset` removes it). The SFTP stack's volume has an `accounts` table without `owner_agent_id`; `make -C services/sftp up` now stops with a message asking you to remove that volume.
- **Race on client deletion.** An account opened between the "has accounts?" check and the delete is orphaned. Recorded in ADR-0035; nothing detects it.

## 7. Noticed, not changed

- ~~`DELETE /api/accounts/{id}` on a missing account returns 500~~ — fixed on the soft-delete branch: missing or deleted accounts, and missing or deleted clients on delete, update and verify, now return 404 for admins too.
- Both Dockerfiles had `ENTRYPOINT ["/api"]`, a path that doesn't exist in the image, so neither container could have started. Fixed in both on this branch, because account-service's Dockerfile was copied from it.
