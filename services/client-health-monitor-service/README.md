# Client Health Monitor Service

## Current status

The module contains the RH-SF00 `ClientHealthFinding V1` handoff, the RH-SF05
Stage 1 task domain, and the provider-independent RH-SF01 transaction replay
boundary. RH-SF02 adds the service-owned activity projection, replay-coverage
tracking, and deterministic account/client baselines. RH-SF03 adds the immutable
`relationship-health-v1` ruleset and typed outcomes for all three MVP signals.

RH-SF01 currently validates the minimum matched transaction projection, follows
bounded cursor pages, de-duplicates identical rows, rejects conflicting or
out-of-window rows, and exposes a result only after every page succeeds. Its
contract-faithful mock supplies six deterministic months for the three positive
signals plus healthy and non-completed controls.

It is intentionally not a runnable application yet. No Spring Boot API,
health-db adapter/migrations, Bedrock adapter, transaction HTTP adapter, or
deployment configuration has been implemented.

RH-SF02 atomically applies complete replays by transaction ID, permits only
Pending-to-terminal status changes, and fails closed on immutable conflicts.
For an 08:00 Asia/Singapore analysis date it retains six months for recurring
credits and calculates a 90-day baseline plus 30-day comparison. Activity counts
use explicit ADR-0051 classification; net-flow summaries are SGD-only.

RH-SF03 independently evaluates recurring-credit interruption, customer
activity decline, and two-period material net outflow under ADR-0052. Outcomes
carry the exact account, rule version, severity, windows, and typed evidence
needed by RH-SF04. Rules do not create tasks, apply cooldown/caps, call Bedrock,
or expose a pseudo-confidence percentage.

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

Contract documentation and fixtures:

- `docs/api/client-health-monitor-contracts.md`
- `docs/api/schemas/client-health-finding-v1.schema.json`
- `docs/api/schemas/transaction-replay-page-v1.schema.json`
- `tests/fixtures/client-health-monitor/`
- `docs/adr/ADR-0051-client-health-explicit-activity-classification.md`
- `docs/adr/ADR-0052-client-health-versioned-signal-rules.md`

## Next implementation step

Feature 3 owners must review ADR-0044 and provide the dedicated authenticated,
date-bounded replay endpoint, stable synthetic source fingerprint, and six
months of reproducible synthetic history. RH-SF01 then replaces the mock with
the HTTP/service-token adapter and runs the same contract tests end to end,
without reading another service's database.

For RH-SF02, coordinate health-db migration ownership with Swayam before adding
the production projection repository. The current repository port and in-memory
tests intentionally keep database technology out of the rule-facing domain.

The next Garvit-owned step is RH-SF04: turn deterministic rule outcomes into
idempotent `ClientHealthFinding` records while applying run leases, signal
fingerprints, cooldown/worsening behavior, and per-agent caps.
