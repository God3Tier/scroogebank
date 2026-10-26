# Client Health Monitor Service

## Current status

The module contains the RH-SF00 `ClientHealthFinding V1` handoff, the RH-SF05
Stage 1 task domain, and the provider-independent RH-SF01 transaction replay
boundary. RH-SF02 adds the service-owned activity projection, replay-coverage
tracking, and deterministic account/client baselines. RH-SF03 adds the immutable
`relationship-health-v1` ruleset and typed outcomes for all three MVP signals.
RH-SF04 adds leased, retry-safe replay-to-finding orchestration.

RH-SF01 currently validates the minimum matched transaction projection, follows
bounded cursor pages, de-duplicates identical rows, rejects conflicting or
out-of-window rows, and exposes a result only after every page succeeds. Its
contract-faithful mock supplies six deterministic months for the three positive
signals plus healthy and non-completed controls.

RH-SF06 now supplies the Spring Boot task API runtime wiring, PostgreSQL task
repository, Contract-B HTTP assignment adapter, current-assignment checks and
bounded disposition policy. Agent reads and actions re-resolve ownership and
hide tasks from a former Agent as `404`; assignment outages fail closed as
`503`. A newly assigned Agent can read and act on an existing task without the
historical assignment snapshot hiding it first. Task lists use opaque Base64URL
keyset cursors over `updated_at` and `task_id`, so inserts and updates do not
shift later pages. Admin task reads remain cross-Agent and read-only under
ADR-0034.

RH-SF02 atomically applies complete replays by transaction ID, permits only
Pending-to-terminal status changes, and fails closed on immutable conflicts.
For an 08:00 Asia/Singapore analysis date it retains six months for recurring
credits and calculates a 90-day baseline plus 30-day comparison. Activity counts
use explicit ADR-0051 classification; net-flow summaries are SGD-only. V4 and
`JdbcActivityProjectionRepository` persist projected transactions and complete
replay-coverage windows atomically across restarts.

RH-SF03 independently evaluates recurring-credit interruption, customer
activity decline, and two-period material net outflow under ADR-0052. Outcomes
carry the exact account, rule version, severity, windows, and typed evidence
needed by RH-SF04. Rules do not create tasks, apply cooldown/caps, call Bedrock,
or expose a pseudo-confidence percentage.

RH-SF04 uses deterministic daily run, finding, signal, correlation and evidence
identifiers; combines every signal for one client/run; and advances its
watermark only after every task handoff returns an explicit outcome. The
task-stream contract now distinguishes created, attached, duplicate, cooldown
suppression and daily-cap suppression. V4 and `JdbcAnalysisRunRepository`
persist attempts, renewable fenced leases, redacted failure categories and
success-only watermarks.

RH-SF05 task persistence migrations now create tasks, assignment snapshots,
evidence, history, daily admissions and idempotent processed-finding outcomes.
V2 enforces strict non-overlapping evidence windows, the three supported signal
types, SHA-256 fingerprints and the closed-task cooldown query index. The JDBC
adapter writes a complete aggregate in one `TransactionTemplate` unit and uses
an explicit version predicate for compare-and-set updates.

The offline V1 rule-evaluation harness runs six labelled synthetic scenario
groups covering positives, healthy controls, exact boundaries, just-below
thresholds, Unknown classification and sparse history. All labels and severities
currently match deterministically; this is contract evidence, not a real-client
accuracy claim.

RH-SF08 currently provides a deterministic redacted Auditor envelope, task and
suppression event mapping, transactional-enqueue/delivery ports, and a bounded
leased relay that continues after individual delivery failures. V3 creates the
health-db outbox, and task creation/evidence/transition/suppression paths enqueue
through the same repository transaction boundary. The production JDBC
task repository now persists those events atomically with task and finding
writes. The leased outbox JDBC adapter, SQS gateway and Auditor allowlist remain
pending.

RH-SF07 generates wording only after a deterministic task create or evidence
attachment succeeds. It sends identifier-free derived facts to Bedrock when
explicitly enabled and otherwise uses deterministic templates. Unsafe,
malformed, timed-out or failed model responses also use templates; explanation
failures cannot alter signal detection, severity, suppression or task admission.
Versioned explanations and their redacted SF08 audit events are committed in
one health-db transaction.

## Ownership boundary

- Garvit owns RH-SF00 through RH-SF04: contracts, transaction intake, activity
  projection, signal rules, and analysis/suppression.
- Swayam owns RH-SF05 through RH-SF07: task domain/persistence, task
  API/authorization, and Bedrock/template explanations.

Garvit's stream produces `ClientHealthFinding`. Swayam's stream implements
`RelationshipTaskPort`.

## Build and test

The project requires Java 25.

From the repository root:

```sh
mvn -pl services/client-health-monitor-service -am test
```

PostgreSQL repository and full runtime integration tests use Testcontainers and
run when Docker is available. The runtime test starts the real Spring context,
applies Flyway migrations, and uses only local HTTP stubs for Cognito machine
tokens and Contract-B assignment responses.

## Local API configuration

The service has no embedded database and does not ship credentials. Set these
properties through environment variables or an external Spring configuration:

| Spring property | Environment form | Purpose |
|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | JDBC URL for health-db |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | health-db application login |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | health-db application password |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Cognito user-pool issuer |
| `idp.cognito.app-client-id` | `IDP_COGNITO_APP_CLIENT_ID` | CRM access-token client ID |
| `client-health.assignment.base-url` | `CLIENT_HEALTH_ASSIGNMENT_BASE_URL` | client-profile internal ALB URL |
| `client-health.assignment.oauth.token-uri` | `CLIENT_HEALTH_ASSIGNMENT_OAUTH_TOKEN_URI` | Cognito token endpoint |
| `client-health.assignment.oauth.client-id` | `CLIENT_HEALTH_ASSIGNMENT_OAUTH_CLIENT_ID` | health-service machine client |
| `client-health.assignment.oauth.client-secret` | `CLIENT_HEALTH_ASSIGNMENT_OAUTH_CLIENT_SECRET` | secret supplied outside Git |
| `client-health.assignment.oauth.scope` | `CLIENT_HEALTH_ASSIGNMENT_OAUTH_SCOPE` | assignment-read scope |
| `client-health.ai.enabled` | `CLIENT_HEALTH_AI_ENABLED` | Bedrock kill switch; defaults to `false` |
| `client-health.ai.model-id` | `CLIENT_HEALTH_AI_MODEL_ID` | approved model or inference-profile ID; required when enabled |
| `client-health.ai.timeout` | `CLIENT_HEALTH_AI_TIMEOUT` | per-call timeout; defaults to `5s` |
| `client-health.ai.max-attempts` | `CLIENT_HEALTH_AI_MAX_ATTEMPTS` | bounded total attempts, from 1 to 2 |

With configuration supplied, run from the repository root:

```sh
mvn -pl services/client-health-monitor-service spring-boot:run
```

The application starts with concrete task/query services and repositories.
Assignment-dependent operations cannot succeed until the client-profile owner
implements the currently proposed Contract-B endpoint and provisions the scoped
Cognito client. Blank or unreachable assignment configuration produces `503`
without exposing or mutating a task. AWS still runs Flyway in the separate
`db-migrate` container required by ADR-0028; application-start Flyway is for
local development and tests only. Live Bedrock remains disabled until a model,
token pricing, IAM permissions and environment rollout are approved. Prompts
and generated wording are excluded from logs and audit payloads.

Contract documentation and fixtures:

- `docs/api/client-health-monitor-contracts.md`
- `docs/api/schemas/client-health-finding-v1.schema.json`
- `docs/api/schemas/transaction-replay-page-v1.schema.json`
- `tests/fixtures/client-health-monitor/`
- `docs/adr/ADR-0051-client-health-explicit-activity-classification.md`
- `docs/adr/ADR-0052-client-health-versioned-signal-rules.md`
- `docs/adr/ADR-0053-client-health-run-orchestration-and-task-admission.md`
- `docs/evaluation/client-health-rule-evaluation.md`

## Next implementation step

Feature 3 owners must review ADR-0044 and provide the dedicated authenticated,
date-bounded replay endpoint, stable synthetic source fingerprint, and six
months of reproducible synthetic history. RH-SF01 then replaces the mock with
the HTTP/service-token adapter and runs the same contract tests end to end,
without reading another service's database.

Garvit's RH-SF01-SF04 stream and production SF02/SF04 persistence adapters are
implemented. Remaining integration work includes the real Feature 3 replay
adapter and Java 25 CI. The client-profile Contract-B provider and its scoped
Cognito registration remain external prerequisites for live current-assignment
authorization.

RH-SF08 production follow-up implements the JDBC task/outbox repository against
V1-V3, then an IAM-scoped SQS gateway and the Auditor's reviewed
`RELATIONSHIP_HEALTH_SERVICE` allowlist.
