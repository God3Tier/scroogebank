# Client Health Monitor Service

## Current status

RH-SF00 foundation only. This module currently contains the frozen
`ClientHealthFinding V1` contract and the in-process `RelationshipTaskPort`
used between the detection and task streams.

It is intentionally not a runnable application yet. No Lambda handler, Spring
Boot API, database, Bedrock adapter, SQS producer, or deployment configuration
has been selected or implemented.

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

After RH-SF00 merges into `feat/client-health-monitor`, Swayam can create
`task/client-health-monitor/task-domain` from the updated feature branch and
implement RH-SF05 against the frozen port and records.
