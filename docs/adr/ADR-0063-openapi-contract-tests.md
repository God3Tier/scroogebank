# ADR-0063: OpenAPI contract tests in each service's tests and CI

- Status: Proposed
- Decision date/time: 2026-10-24 22:00 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen; the owners of the services whose contracts were written here should review them (account-service and client-profile-management-service: Feature 2; user-service: Feature 1; the SFTP receiver: Feature 3; the Auditor)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/openapi-contract-tests`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

The quality goals promise maintainability (NFR 3) through contracts: OpenAPI for REST, with additive changes only under `/api`, and a versioned gRPC contract ([ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md)). [ADR-0028](ADR-0028-cicd-migrations-terraform.md) lists "OpenAPI tests" among the CI contract checks. Until now only the gRPC half was checked (`buf lint`, `buf breaking`).

The OpenAPI side had two gaps:

- **Coverage.** Specs existed for the dashboard, the dashboard's aggregates, engagement, event alerts and the activity feed. Users, clients, accounts, transactions and corrupted logs had none, so most of the CRM's REST surface had no contract at all.
- **Nothing checked the specs.** They were hand-kept documents. The team chose on 2026-10-09 to add contract tests for every service at once rather than feature by feature.

The services are Java (Spring Boot, tested with MockMvc) and Go (gin, tested with `httptest`). Their tests already exercise every handler with mocked dependencies, and the Go tests run in containers that mount only their own service.

## Decision Drivers

- One approach for Java and Go, run where each service's tests already run.
- Catch drift both ways: a response the spec does not describe, and a route the spec does not list.
- No new infrastructure: no running stack, Cognito tokens or databases beyond those the tests already use.
- Breaking changes for callers are caught at review, as `buf breaking` does for gRPC.
- Library versions follow the project rule: the newest release at least two weeks old.

## Options Considered

### Option A: validate inside each service's tests, and diff specs in CI (chosen)

- Pros: runs in the existing test jobs; every test that calls the API also checks the contract; errors point at the test that made the call.
- Cons: checks only what the tests exercise; one helper copy per service.

### Option B: lint and diff the specs only

- Pros: cheapest.
- Cons: nothing shows that the code matches the spec.

### Option C: fuzz the running services with Schemathesis

- Pros: the most thorough; finds inputs no test thought of.
- Cons: needs the whole stack in CI, with real tokens, databases and the services that call each other; slow and brittle.

## Decision

1. **Every REST route has an OpenAPI 3.0.3 contract in `docs/api`.** New specs were written from the code: `users-api.yaml`, `clients-api.yaml`, `accounts-api.yaml`, `transactions-api.yaml` and `corrupted-logs-api.yaml`. They record what the services do today, including the Go services' mix of JSON and plain-text errors. Paths served by two services (user-service and the receiver for transactions; user-service and the Auditor for corrupted logs) share one spec that lists the union of their responses. The two 3.1 specs (`openapi.yaml`, `audit-activity.yaml`) moved to 3.0.3, because the Java validator does not support 3.1. A nullable reference is written as `anyOf` the schema and a `NullValue` schema, which both validators read the same way.

2. **Java services check every MockMvc response.** A test auto-configuration, listed in each service's `META-INF/spring/...AutoConfigureMockMvc.imports`, adds a result matcher to every auto-configured MockMvc, so no test opts in or out. It uses Atlassian's `swagger-request-validator` 2.46.1. A route, method or status that no contract lists fails the test, and so does a body that does not match its schema. Undocumented response fields also fail: the validator treats schemas as closed. Requests are not checked, so tests may still send bad input on purpose. Error responses may lack a body: MockMvc does not run the servlet container's error page, which adds one when deployed.

3. **Go services check controller responses.** Each Go module has a test-only `contract` package using `kin-openapi` v0.149.0. Controller tests call `contract.Check` with the operation's method and path template, since they call handlers directly. Router tests call `contract.CheckRoutes` on the gin router, so a route no test calls must still be in a contract. The test containers mount `docs/api` read-only and set `OPENAPI_DIR`.

4. **Route coverage in Java too.** Each Java service has an `OpenApiRoutesTest` that scans its `@RestController`s and fails for any route missing from its contracts.

5. **Consumers check their canned replies.** Where a service's tests stand in for another service (dashboard-service's adapters for the aggregates and the user roster, engagement-service's client lookup), the normal canned replies are checked against the provider's contract first. Both sides of those calls are then tested against the same file.

6. **CI checks the files.** A `Check the OpenAPI contracts` job validates every spec with `openapi-spec-validator` 0.9.0. On pull requests, `oasdiff breaking` (v1.33.0) compares each spec with the target branch's copy and fails on any breaking change at ERR level. A removed spec fails, and so does one that is not OpenAPI 3.0. A target-branch copy still in 3.1 is first converted to 3.0 with `openapi-down-convert` 0.14.2, because oasdiff reads 3.1's nullable types as not nullable and would report changes that are not there.

7. **Breaking changes can be accepted, in writing.** `docs/api/accepted-breaking-changes.txt` lists each accepted change as oasdiff reports it, under a comment giving the reason; the check passes it to `--err-ignore`. An entry stays until the change reaches `main`, since each promotion compares with its own target branch. The first entry is the activity feed's `payload`, which the spec described as always an object; user-service has always passed a payload that is not JSON on as a string, and the frontend already treats one that is not an object as empty.

8. **The helper is copied, not shared.** Each service has its own copy of the small helper: Java services build their Docker images from their own POM, and Go services test in containers that see only their own module, so a shared module would break both.

## Rationale

Option A gives most of Option C's assurance for the routes the tests cover, at no extra infrastructure cost. The route checks close the gap for routes no test calls, and the consumer checks make each cross-service call a contract both sides test against. Writing the missing specs from the code, rather than from intent, makes the first run a record of current behaviour. Mismatches found while writing them were either fixed as clear defects or are listed below for their owners.

## Consequences

### Positive

- A response that drifts from its contract fails the test that produced it, in the service's normal test run.
- A route without a contract fails, so no API grows undocumented.
- A pull request that breaks a caller fails CI before review.
- Test fixtures now look like real data. Several returned zero values the database never would.

### Negative / Trade-offs

- Only exercised responses are checked; an untested branch can still drift.
- The helper exists in four Java and four Go copies; a change to it is made in each.
- Java checks are stricter than Go ones: Atlassian treats unlisted response fields as errors, while kin-openapi allows them.
- Specs that two services serve list the union of their responses, so each service's own subset is not separated.
- A deliberate breaking change needs an entry in `accepted-breaking-changes.txt`, reviewed like any other change; nothing else overrides the check.

## Security & Privacy Impact

None to runtime behaviour. The specs describe existing routes, scopes and errors; examples are synthetic. The CI job uses public images and packages at pinned versions and only reads the repository.

## Cost Impact

None beyond a short CI job: the specs are validated, and oasdiff runs in a container, on pull requests only.

## Operational Impact

Service owners keep their specs in step with their code. When a test fails on a contract, either the code or the spec is wrong, and the pull request says which.

Defects found while writing the specs, and fixed here:

- Four Go handlers wrote JSON without a JSON `Content-Type` (account delete; client get, update and delete), so it was sniffed as `text/plain`.
- user-service passes the Auditor's corrupted-log replies on by parsing them as JSON, but the Auditor wrote its errors as plain text, so a 400 or 404 from the Auditor reached the admin as a 500. The Auditor now writes those errors as JSON `{"error": ...}`, like its authentication middleware. No frontend page calls these routes.
- account-service's `PATCH /api/accounts/{id}` answered the new status in capitals, while `GET` and the stored row use lower case. It now answers in lower case. The frontend ignores this reply and compares statuses in any case; the audit event still records the statuses in capitals.
- account-service's security tests and repository tests used the same account IDs against one database in parallel packages, which made `TestSetOwner` fail at random.

## Validation

- Every spec in `docs/api` passes `openapi-spec-validator` 0.9.0.
- `mvn verify` passes for all Java modules, with every MockMvc response checked.
- `make test` passes for account-service, client-profile-management-service, the SFTP receiver and the Auditor, with controller responses and router routes checked.
- Deliberately breaking a spec (renaming a path, adding a required field, removing a route) fails the expected tests.
- `oasdiff breaking` against `dev`, with the 3.1 copies converted to 3.0, reports one breaking change, the accepted `payload` one; without the conversion it reported 21 nullability changes that were not there.
- OSV-Scanner reports no new vulnerability; the validator's Rhino is pinned to 1.7.15.1.

## References

- [ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md), [ADR-0028](ADR-0028-cicd-migrations-terraform.md)
- [Test strategy](../testing/test-strategy.md), Maintainability row
- Atlassian swagger-request-validator; kin-openapi; oasdiff; openapi-spec-validator
