# ADR-0064: transaction-service checks its own callers, and callers read transactions from it directly

- Status: Accepted
- Decision date/time: 2026-10-26 14:30 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen; Joseph Yau (Feature 3 owner and owner of the receiver code this changes, and of ADR-0006, who agreed on 2026-10-26)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/transaction-integration/direct-transaction-reads`
- Related issue/PR: TBD
- Supersedes: [ADR-0006](ADR-0006-transaction-account-linkage-and-access.md) decisions 3 and 5; [ADR-0039](ADR-0039-dashboard-aggregate-endpoints.md) decisions 4 and 5, and decision 2's exception for the receiver; [ADR-0043](ADR-0043-client-and-transaction-list-endpoints.md) decision 2 (which service serves the admin transaction list) and decision 3 (who audits it)
- Superseded by: N/A

## Context

transaction-service is the SFTP receiver's read API (`services/sftp/receiver/src/serverd`), deployed as its own ECS service. It checks no token. user-service stands in front of it ([ADR-0006](ADR-0006-transaction-account-linkage-and-access.md) decisions 3 and 5). For each transaction read, user-service:

1. checks the caller's role;
2. asks account-service whether the caller may see the account (`GET /api/accounts/{id}`), or which accounts they own (`GET /aggregates/accounts/ids`);
3. calls transaction-service;
4. audits the read.

[ADR-0039](ADR-0039-dashboard-aggregate-endpoints.md) and [ADR-0043](ADR-0043-client-and-transaction-list-endpoints.md) kept this pattern for the dashboard's totals and the admin transaction list. A security group lets only user-service reach transaction-service.

Four problems follow:

- **Ownership.** Rules on who may read transactions live in the user-management service, against [ADR-0017](ADR-0017-microservices-per-business-area.md)'s one service per business area. A change to transaction reads needs a change in Feature 1's code.
- **Network trust.** The [security model](../security/security-model.md) and [ADR-0037](ADR-0037-transactions-database-and-account-linking.md) say no call is trusted because of where it comes from. transaction-service is the one service that relies on the network alone.
- **Coupling and hops.** If user-service is down, no one can read transactions. The dashboard's transactions panel passes through dashboard-service, user-service, account-service and transaction-service.
- **Other callers.** The health monitor needs to read transaction activity ([open item 16](../architecture/open-items.md#16-health-monitor-activity-contract)). It has no user, so it cannot go through user-service's user-token routes.

## Decision Drivers

- No call is trusted because of where it comes from.
- An agent sees only transactions on accounts they own; an admin sees all linked transactions. The scope comes from the verified token only.
- Every `/api` transaction read is audited, as today.
- Public paths and response shapes stay the same, so the frontend does not change.
- Each audit producer's events are sent under its own IAM role, and two components do not share an outbox table ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md), [decision log](../architecture/decision-log.md)).

## Options Considered

### Option A: Keep user-service in front (status quo)

- Pros: no work; transaction-service stays simple.
- Cons: all four problems above remain.

### Option B: transaction-service checks its own callers; callers go to it directly (chosen)

- Pros: transaction reads are decided where transactions live; transaction-service checks tokens like every other service; one fewer service in each read; a service-token route for the health monitor can be added later the same way as account-service's `/internal`.
- Cons: transaction-service needs token checks, an account-service client, its own audit outbox, TLS and an ALB route. The auditor must accept a new kind of event from `TRANSACTION_SERVICE`.

### Option C: Open the security group to more callers without adding checks

- Rejected. It makes an unauthenticated API reachable from more places.

## Decision

**Option B.**

1. **transaction-service checks every caller's token**, with its own copy of account-service's Cognito verifier. As in account-service, it accepts only user access tokens issued to the CRM web app client, with the `ADMIN` or `AGENT` group. Only `/health` is open.
2. **Routes** (paths and response shapes unchanged):

   | Route | Who | Scope |
   |---|---|---|
   | `GET /api/accounts/{accountId}/transactions` | `ADMIN`, `AGENT` | transaction-service forwards the caller's token to account-service's `GET /api/accounts/{id}`. It reads transactions only after a 2xx. account-service's 404 is returned as is, so agents cannot discover other agents' account IDs. |
   | `GET /api/transactions?cursor&size&status` | `ADMIN` only; agents get 403 | every linked transaction |
   | `GET /aggregates/transactions/stats?from&to` | `ADMIN`, `AGENT` | An admin gets every transaction. For an agent, the account IDs come from account-service's `GET /aggregates/accounts/ids`, called with the caller's token. |

   The receiver's unauthenticated `POST /aggregates/transactions/stats` is removed. No route takes an account list or an `all` flag from the caller.
3. **Callers go direct.**
   - The ALB sends `/api/accounts/*/transactions` and `/api/transactions` to transaction-service.
   - dashboard-service calls `GET /aggregates/transactions/stats` on transaction-service over Service Connect.
   - user-service's transaction routes and proxies are removed, and it can no longer reach transaction-service.
4. **Auditing.** transaction-service audits the two `/api` reads:
   - `source_service=TRANSACTION_SERVICE`, `event_type=CRUD`, `action=READ`, `actor_id` = the token's `sub`;
   - the payload has the same fields user-service sends today: `resource_type` (`ACCOUNT` with its ID, or `TRANSACTION` with none), endpoint, method, status code, role and time.

   The auditor accepts these events. `/aggregates` is not audited: the dashboard audits each view (ADR-0039 decision 6).
5. **Its own outbox.**
   - The events go to a new `read_audit_outbox` table in transactions-db, not to the import's `audit_outbox`. transaction-service relays it to the shared queue under its own IAM role.
   - transaction-service's database login may read, insert and delete in `read_audit_outbox`, and still only read `transactions`. The ingest Lambda's login loses all rights on the new table.
   - So each outbox has one writer and one sender, as ADR-0020 requires.
6. **Transport.** transaction-service now receives user tokens and forwards them to account-service. In production it serves HTTPS and calls account-service over HTTPS, as account-service does.
7. **Location.** The code stays in `services/sftp/receiver`. Renaming or moving the folder is a separate decision for its owner.
8. **Not in this decision.** A service-token route for the health monitor (open item 16). It can be added later as an `/internal` route that accepts only service tokens, as in ADR-0037.

## Rationale

account-service already answers who owns an account, and transaction-service already holds the transactions, so the check belongs between those two. user-service added no information of its own. Checking the token in transaction-service closes the last exception to "no network trust". A separate outbox keeps each producer's events under its own IAM role, and gives each table one writer and one sender. That is why ADR-0020 rejected a shared outbox and relaying another component's events.

## Consequences

### Positive

- Every service checks its own callers; the security model has no transaction-service exception.
- user-service no longer depends on account-service or transaction-service for transactions, and its audit middleware covers only its own resources.
- One fewer service in every transaction read, and in the dashboard's transactions panel.
- A later health-monitor route needs only a route group and a Cognito app client.

### Negative / Trade-offs

- transaction-service gains a Cognito verifier, an account-service client, an outbox relay, TLS and an ALB route. It now has to be up for transaction reads, as before, and account-service too, as before.
- A third copy of the Cognito verifier in Go to keep in step.
- The auditor must be deployed before transaction-service, or the new events are rejected and stay in the outbox until it is.
- Transaction reads in the audit trail change source, from `USER_MANAGEMENT` to `TRANSACTION_SERVICE`. Anyone filtering past events by source must look for both.

## Security & Privacy Impact

- **Authentication** moves from network position to a verified token. The security group still allows only the ALB and dashboard-service as defence in depth.
- **Authorization** is unchanged in substance: account-service decides ownership, as since ADR-0035. The scope still comes only from the token.
- **Tokens** are forwarded over HTTPS only in production. They are not stored or logged.
- **Audit:** each read is recorded by the service that serves it. Audit payloads carry IDs only, never transaction contents.

## Cost Impact

None measurable. There is one more ALB rule target and a TLS certificate from the existing private CA. The relay polls an empty table about every two seconds.

## Operational Impact

- transaction-service needs these settings:
  - `APP_ENV`;
  - TLS certificate and key paths;
  - `COGNITO_ISSUER_URI` and `COGNITO_APP_CLIENT_ID`;
  - `ACCOUNT_SERVICE_BASE_URL`;
  - `AUDIT_QUEUE_URL` and `AWS_REGION`;
  - an IAM permission to send to the audit queue, and a place among the queue's producer roles.
- dashboard-service needs `TRANSACTION_SERVICE_BASE_URL`. user-service no longer needs `SFTP_RECEIVER_BASE_URL`.
- Deploy order: auditor, then the database migration, then transaction-service. Every deployment ends with a full teardown ([ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md)), so there is no running system to migrate gradually.

## Validation

- **transaction-service:**
  - token tests with signed tokens: no token gives 401, a token from another app client gives 401, a missing role gives 403;
  - route tests: an agent gets 404 for another agent's account; agents get 403 on the admin list; an agent's totals cover only their own account IDs, and an empty list gives zeros;
  - audit middleware and relay tests;
  - OpenAPI contract checks on every route ([ADR-0063](ADR-0063-openapi-contract-tests.md)).
- **Auditor:** payload tests accept `TRANSACTION_SERVICE` `READ` events for `ACCOUNT` (with an ID) and `TRANSACTION` (without one), and reject anything else.
- **user-service and dashboard-service:** their test suites pass, with the transaction routes removed and the dashboard pointed at the new upstream.
- **End to end:** the same checks through API Gateway with real Cognito users, and the auditor storing the new events.

## References

- [ADR-0006](ADR-0006-transaction-account-linkage-and-access.md), [ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md), [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md), [ADR-0035](ADR-0035-account-ownership-across-services.md), [ADR-0037](ADR-0037-transactions-database-and-account-linking.md), [ADR-0039](ADR-0039-dashboard-aggregate-endpoints.md), [ADR-0043](ADR-0043-client-and-transaction-list-endpoints.md), [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md), [ADR-0063](ADR-0063-openapi-contract-tests.md)
- [Security model](../security/security-model.md), [open item 10](../architecture/open-items.md#10-how-transaction-reads-enforce-agent-ownership), [open item 16](../architecture/open-items.md#16-health-monitor-activity-contract)
