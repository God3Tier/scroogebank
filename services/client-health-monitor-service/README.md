# Client Health Monitor Service

## Current status

The module contains the RH-SF00 `ClientHealthFinding V1` handoff, the RH-SF05
Stage 1 task domain, and the provider-independent RH-SF01 transaction replay
boundary.

RH-SF01 currently validates the minimum matched transaction projection, follows
bounded cursor pages, de-duplicates identical rows, rejects conflicting or
out-of-window rows, and exposes a result only after every page succeeds.

It is intentionally not a runnable application yet. No Lambda handler, Spring
Boot API, database, Bedrock adapter, transaction HTTP adapter, or deployment
configuration has been selected or implemented.

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
- `tests/fixtures/client-health-monitor/`

## Next implementation step

Feature 3 owners must review ADR-0044 and provide the dedicated authenticated,
date-bounded replay endpoint, stable synthetic source fingerprint, and six
months of reproducible synthetic history. RH-SF01 can then add the HTTP/service
token adapter and provider contract fixtures without reading another service's
database.
