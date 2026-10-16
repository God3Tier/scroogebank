# ADR-0017: One microservice per business area, with a Go runtime exception

- Status: Accepted
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: in part, [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 7 (the Feature 3 runtime, 2026-10-14)

## Context

The CRM covers user management (Feature 1), client and account management (Feature 2), transaction import and query (Feature 3), three x-factor features (Features 4–6) and a tamper-resistant audit log. Eight developers work on these features in parallel, each feature with its own owners and branch.

`project_plan.md` §4 says the folder structure must not imply an undecided architecture, so whether the back end is a modular monolith or a set of microservices needs an ADR. The repository already contains separate deployables: `user-service` (Java), `client-profile-management-service` (Go, holding both clients and accounts), the `auditor` (Go), `dashboard-service` and `client-health-monitor-service` (Java).

`project_plan.md` §2.2 makes Java 25 the default runtime and requires an ADR for any other runtime that becomes part of the deployed architecture. Two existing services are written in Go.

## Decision Drivers

- Maintainability and clean boundaries (NFR 3, NFR 11)
- Independent scaling and failure isolation (NFR 1, NFR 2)
- Parallel delivery by separate feature owners
- Data ownership: no service reads another service's tables
- Cost within the USD 300 limit
- Team capability and existing code

## Options Considered

### Option A: modular monolith

One deployable with internal modules per business area, sharing one database.

- Pros: one deployment, one database, lowest infrastructure cost, simple local development.
- Cons: one failure domain; one scaling unit; module boundaries are enforced only by convention; features owned by different people collide in one codebase and release; a shared database invites cross-module table access.

### Option B: one microservice per business area (chosen)

user-service, client-service, account-service, transaction-service (plus the transaction-ingest worker), the auditor, and one service per x-factor (event-alert-service, dashboard-service, client-health-monitor-service). Each owns its own database, and other services reach its data only through its API.

- Pros: independent deployment, scaling and failure; clear ownership per feature; data ownership enforced by credentials and security groups.
- Cons: more infrastructure (one ECS service and one database per service); cross-service calls need timeouts, retries and authorization; consistency across services is eventual.

### Option C: microservices, but accounts stay inside the client service

Keep today's `client-profile-management-service` holding both clients and accounts.

- Pros: no split work; the accounts-to-clients foreign key keeps working.
- Cons: accounts and clients cannot scale or change independently; it does not match the separate account-service the target architecture assigns.

## Decision

Use **one microservice per business area** (Option B), each with its own database and its own ECS service:

| Service | Feature | Runtime |
|---|---|---|
| user-service | F1 | Java 25 |
| client-service | F2 (clients) | Go (today's `client-profile-management-service`) |
| account-service | F2 (accounts) | Go (extracted from `client-profile-management-service`'s account code; decided 2026-10-08) |
| transaction-service and transaction-ingest | F3 | Go (ingest on Lambda); superseded on 2026-10-14 by [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 7, which replaced Java 25 |
| event-alert-service | x-factor 1 | Java 25 |
| dashboard-service | x-factor 2 | Java 25 |
| client-health-monitor-service | x-factor 3 | Java 25 |
| auditor (and chain-verify) | Audit | Go |

Accounts are split out of `client-profile-management-service` into account-service. The accounts-to-clients foreign key is replaced by an API check when an account is created, and by client-service refusing to delete a client who still has open accounts. How those checks work, and how account-service knows an account's owning agent, is decided in [ADR-0035](ADR-0035-account-ownership-across-services.md).

**Go runtime exception:** the auditor, client-service and account-service may stay in Go. Every other new service uses Java 25. account-service was added to the exception on 2026-10-08: it is extracted from the existing, tested Go account code rather than rewritten, so it shares client-service's toolchain, JWT verification and audit middleware. transaction-service and transaction-ingest were added on 2026-10-14 by [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 7: the SFTP receiver, which holds both, is already built and tested in Go.

## Rationale

- Each feature has separate owners; separate services let them build, test and deploy without blocking each other.
- One database per service makes "no service reads another's tables" a property of credentials and security groups, not of convention.
- Services scale and fail independently: a slow import or a dashboard burst does not take down user management.
- The Go exception avoids rewriting two working services (the auditor's hash-chain and SQS consumer, and the Feature 2 service) late in the project. Rewriting them would cost delivery time with no functional gain. The same reasoning covers account-service, which is the Feature 2 service's existing account code moved into its own deployable.

## Consequences

### Positive

- Clear bounded contexts and ownership.
- Independent scaling (2–6 Fargate tasks per service) and failure isolation.
- Contracts between services are explicit (OpenAPI for REST, `.proto` for gRPC).

### Negative / Trade-offs

- Eight ECS services and seven databases cost more than a monolith ([environments and cost](../cost/budget.md)).
- Cross-service calls need timeouts, retries, circuit breakers and per-call authorization.
- Two runtimes (Java and Go) mean two toolchains in CI, two sets of libraries to patch, and two places to implement JWT validation and audit publishing.
- Splitting accounts from clients is required work ([open item 6](../architecture/open-items.md#6-splitting-accounts-out-of-client-profile-management-service)).

## Security & Privacy Impact

Each service has its own IAM task role, security group and database credentials, so a compromised service reaches only its own data. Every service validates the user's JWT itself ([ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md)). Go services must implement the same JWT checks, PII log masking and dependency scanning as Java services.

## Cost Impact

If left on 24/7: roughly USD 325–365/month for Fargate (8 services × 2–2.25 tasks of 0.25–0.5 vCPU and 1 GB, plus scheduled jobs) and USD 140–155/month for seven single-AZ RDS instances. The lifecycle in [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md) keeps this inside the USD 300 limit.

## Operational Impact

- One ECS service, task definition, log group, alarm set and database per service, built from a reusable Terraform module.
- CI builds and scans both Java and Go images; Go versions are pinned in the build files, CI configuration and runbooks (§2.2).
- The auditor, client-service and account-service owners own the Go toolchain and its patching. account-service and client-service each carry a copy of the Cognito JWT verification and SQS audit middleware, which must be kept in step.

## Validation

- Each service builds, tests and deploys on its own in CI.
- An authorization test shows one service cannot reach another service's database (security groups and credentials).
- Load tests show services scaling independently.

## References

- [System overview: components](../architecture/system-overview.md#components)
- [Database design](../database/schema.md)
- [Open item 6: splitting accounts out](../architecture/open-items.md#6-splitting-accounts-out-of-client-profile-management-service)
- [Open item 7: Go runtime exception](../architecture/open-items.md#7-go-runtime-exception)
- `project_plan.md` §2.2 (runtime baseline) and §4 (repository rule)
- [`docs/feature-plans/feature-2_plan.md`](../feature-plans/feature-2_plan.md)
