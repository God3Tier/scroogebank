# Event Alert Service

Java 25 Spring Boot foundation for Dynamic Event-Driven Client Alerts. The same
artifact supports one explicit mode at a time:

- `ALERT_MODE=API` for authenticated CRM alert operations.
- `ALERT_MODE=WORKER` for bounded scheduled processing.

The current foundation is offline and deterministic. It contains no AWS SDK,
provider credentials, or outbound provider calls. Synthetic holdings are loaded
from `src/main/resources/fixtures/holdings-snapshot.v1.json`.

The AI boundary is independently disabled by default with
`ALERT_AI_ENABLED=false` and `ALERT_AI_PROVIDER=none`. The adapters accept public evidence only,
screen prompt-injection/private identifiers before invocation, send versioned
closed-schema requests through `AiModelGateway`, validate citations and candidate
instruments, cap input/output/retries/timeouts, and safely abstain or retain an
event for deterministic rule review. Briefings are explicitly AI-labelled,
source-cited, uncertainty-bearing, and rejected when they contain advice
language.

An opt-in Bedrock Runtime implementation uses the Converse API with the pinned
global Luna inference profile, AWS default credential chain, configured region,
zero temperature, output-token cap, and per-request timeout. Enabling it requires
both `ALERT_AI_PROVIDER=bedrock` and `ALERT_AI_ENABLED=true`; keep both disabled
until ADR-0010 is accepted and the benchmark gates pass. The committed tests mock
the SDK and make no AWS call. Never put credentials in repository configuration.

The read adapter requires an assigned-agent subject and never returns another
agent's positions. `materiality-v1` uses fixed-decimal, versioned weights and a
60.00 threshold. Stale valuations carry a visible 10-point penalty; missing or
expired valuations fail closed and cannot produce an alert score. These are MVP
prototype controls, not investment policy.

## Local verification

Docker must be running because repository tests use PostgreSQL Testcontainers.

```powershell
mvn -B -pl services/event-alert-service -am verify
```

Runtime database settings are supplied through `EVENT_ALERT_DB_URL`,
`EVENT_ALERT_DB_USERNAME`, and `EVENT_ALERT_DB_PASSWORD`. Never commit real
credentials. Flyway owns the schema and Hibernate validates it at startup.

API mode also requires `COGNITO_ISSUER_URI`. JWT subjects identify actors and
the `cognito:groups` claim supplies the `AGENT` or `ADMIN` role. Agents can post
decisions and feedback only for assigned alerts; admins are read-only. Mutation
requests require `Idempotency-Key`, accept `X-Correlation-Id`, and return `503`
without committing the mutation when durable audit persistence is unavailable.
The test profile uses a non-routable example issuer and mocked/local security;
local verification does not contact Cognito or any AWS service.

`GET /api/alerts` returns a stable, cursor-paginated list. Agent results are
scoped in the database query to the authenticated subject; admins receive the
global read-only view. Optional `status`, `minimumScore`, `cursor`, and `limit`
parameters match the OpenAPI contract.

`GET /api/alerts/{alertId}` applies the same database-level visibility rule and
returns exposure, ordered explanation factors, public-source evidence, the
structured briefing when available, and the latest recorded decision. Alerts
outside an agent's assignment scope return `404` to prevent enumeration.
