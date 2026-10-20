# ADR-0033: X-factor 2 business dashboard: stateless fan-out, 3-minute in-process cache, best-effort audit

- Status: Accepted
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

X-factor 2 gives admins bank-wide and per-agent figures and gives agents a view of their own book. The figures come from four services that each own their data:
- user-service;
- client-service;
- account-service;
- transaction-service.

Data ownership must not be broken ([ADR-0017](ADR-0017-microservices-per-business-area.md)), and an agent must never see another agent's figures. The pages ship inside the existing frontend.

`feat/business-dashboard` has Proposed ADRs with no named owner yet:
- **branch ADR-0001** (aggregation topology): agrees with this ADR;
- **branch ADR-0003** (read audit events): sends audit events through logs, not SQS, which differs from this ADR;
- **branch ADR-0004** (cache and staleness): agrees on the 3-minute TTL.

Those files use ADR numbers already taken on `dev`.

## Decision Drivers

- Data ownership and agent scoping
- Cost (no extra database for derived data)
- Load on the four source services
- Testability of the aggregation

## Options Considered

### Topology

- **Stateless dashboard-service; each owning service computes its own aggregates (pushdown), fetched with the caller's JWT (chosen).**
- **Its own RDS read model:** a costly extra instance, and a second copy of client data to secure and erase.
- **Event-sourced projection over a broker:** infrastructure for its own sake.
- **Composing in the browser:** cross-service joins and failure handling move into the frontend; payloads are untestable; more round trips.
- **Reading other services' databases directly:** breaks data ownership.

### Access control

- **Forward the caller's JWT; each service scopes its own answer (chosen).**
- **A service credential for the dashboard:** it could read more than the caller, and the ownership rule would be duplicated.

### Cache

- **In-process Caffeine per task, 3 minutes, keyed by caller (chosen).**
- **ElastiCache:** a billable cluster for a small dataset.
- **No cache:** every refresh fans out to four services.

### Audit

- **`DASHBOARD_VIEW` events sent straight to SQS, best-effort; cache hits still audited (chosen).**
- **An outbox:** there is no database to hold one.
- **Failing the request when auditing fails:** auditing a read must not take the read down.
- **Logs only (branch ADR-0003):** the events would not reach the hash-chained audit log.

## Decision

1. **dashboard-service** is a stateless Java 25 ECS service with **no database**.
2. It fans out over ECS Service Connect to aggregate endpoints on user-, client-, account- and transaction-service, **forwarding the caller's JWT**. Each service scopes its own answer.
3. It caches each caller's result in process (Caffeine) for **3 minutes**. A result with a panel that timed out or failed (`upstream_timeout`, `upstream_error`) is not kept, so the next request asks again (amended 2026-10-19).
4. It sends `DASHBOARD_VIEW` audit events straight to the shared audit queue, **best-effort** ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)).

## Rationale

- Pushdown keeps every figure computed where the data lives, with the owner's own authorization rules.
- The cache cuts the fan-out cost of repeated refreshes without new infrastructure.
- Best-effort auditing records views without making reads depend on SQS.

## Consequences

### Positive

- No new data store and nothing extra to erase.
- Agent scoping is enforced by each source service.
- Cheap to run.

### Negative / Trade-offs

- Figures can be up to 3 minutes stale.
- While an upstream is failing, every refresh asks every upstream again, the failing one included: the cache no longer shields it.
- Each task has its own cache, so different tasks may briefly show different figures.
- Each source service must build and maintain aggregate endpoints. Only the user roster adapter exists today.
- A failed audit send means a view goes unrecorded.

## Security & Privacy Impact

- The dashboard never holds more privilege than the caller.
- Cache entries are keyed by caller, so one user's figures are never served to another.
- No personal data is stored.

## Cost Impact

One small ECS service. No database or cache cluster.

## Operational Impact

- Fan-out latency and error metrics.
- Per-call (10 s) and overall (15 s) timeouts, with each panel resolved on its own and no retries, so a down service shows a partial dashboard rather than an error. Raised from 2 s and 3 s on 2026-10-19, then from 5 s and 8 s on 2026-10-20. The overall timeout stays under API Gateway's 29 s integration timeout.
- An upstream that answers with an error or cannot be reached is logged as a warning with its cause; expected unavailability (not deployed, refused, mixed currencies, unsupported window) stays at debug.
- The cache TTL is in configuration.

## Validation

- A repeat refresh within the TTL causes no extra calls to the source services, unless a panel timed out or failed.
- An agent's dashboard never includes another agent's clients.
- With one source service down, the dashboard degrades gracefully.

## References

- [X-factor 2: business dashboard](../architecture/x-factors.md#x-factor-2-business-dashboard)
- [Numbered flow 12](../architecture/data-flow.md#numbered-flows)
- `feat/business-dashboard`: ADR-0001, ADR-0003, ADR-0004 (Proposed, owner to be named) and `business-dashboard_plan.md`

## Revision history

- 2026-10-01 21:50 +08:00: proposed.
- 2026-10-09: accepted by the decision owner.
- 2026-10-19: amended by the decision owner after the first AWS test deployment, where panels exceeded the 2 s budget while their upstreams answered in milliseconds, mostly just after a restart. Each failure was then cached for the full 3 minutes, and upstream errors left nothing in the log at the default level.
  - **Decision 3:** a result with a timed-out or failed panel is not cached. Results whose panels are unavailable for reasons a retry does not change are still cached.
  - **Operational Impact:** timeouts raised to 5 s per call and 8 s overall; upstream errors logged as warnings with their cause.
- 2026-10-20: amended by the decision owner. **Operational Impact:** timeouts raised again, to 10 s per call and 15 s overall, to give upstreams more room just after a restart. The overall timeout stays under API Gateway's 29 s integration timeout, so a slow dashboard still answers with a partial result rather than the gateway's timeout error. The cost is that a failing panel now takes up to 15 s, not 8 s, to show as unavailable.
