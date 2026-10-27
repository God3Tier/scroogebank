# Client Health Monitor — Proposed Contracts

## Status and ownership

- Status: Proposed for SF00 review; not an accepted production contract
- Feature: Client Health Monitor
- Contract owner: Garvit
- Required reviewer: Swayam
- Required external reviewers: transaction owner, client-profile owner, Auditor owner, security/platform owner
- Feature plan: [`../feature-plans/client-health-monitor_plan.md`](../feature-plans/client-health-monitor_plan.md)
- Last updated: 2026-09-29

These contracts freeze boundaries for independent development. They do not select an AWS transport, authorize changes in another service, or replace required ADRs.

## Contract principles

1. Every external contract is versioned.
2. Canonical clients, accounts, and transactions remain owned by their source services.
3. The health service never reads another service's database directly.
4. At-least-once input is assumed; consumers are idempotent.
5. Unmatched or rejected transactions are never analysed.
6. Decimal monetary values are strings in JSON to avoid floating-point loss.
7. Timestamps use ISO 8601 UTC.
8. Identifiers are opaque; consumers do not infer meaning from their format.
9. User identity comes from verified Cognito claims.
10. AI receives derived non-identifying facts, never these raw external records.
11. Contract tests and fixtures must change with every schema change.
12. Breaking changes require a new major schema version and migration period.

## Current repository facts

### Transaction import

The merged SFTP receiver currently stores:

- transaction ID;
- source account reference;
- Deposit/Withdrawal type;
- amount;
- currency;
- transaction timestamp;
- Completed/Pending/Failed status;
- free-text description;
- nullable client ID; and
- Unmatched/Matched/Rejected import status.

Current gaps:

- save logic does not populate client ID;
- no account-to-client matching contract;
- no event publisher;
- no read API;
- no historical replay/export;
- no stable source/counterparty fingerprint; and
- no authoritative balance snapshots.

### Client assignment

The client-profile service stores the owning agent's Cognito subject in `assigned_agent_id`, which an admin's reassignment changes ([ADR-0060](../adr/ADR-0060-client-reassignment.md)), but exposes no machine-to-machine assignment contract.

### Auditor

The merged Auditor consumes a standard Amazon SQS queue. Its current event envelope contains:

```text
event_id
event_type
action
source_service
actor_id
payload
```

The current allowlist does not recognise this feature's proposed service type or payload.

## Contract A: Matched Transaction Activity V1

### Purpose

Provide transaction facts only after durable import and successful account/client matching.

The logical schema is transport-neutral. A future ADR will decide whether it is delivered as:

- an at-least-once event; or
- a paginated internal read/replay API.

### Proposed event

Event name:

```text
transaction.matched.v1
```

Example:

```json
{
  "schemaVersion": "1",
  "eventId": "6b50a97e-8a88-4e64-a3df-a686745223cc",
  "occurredAt": "2026-09-29T04:00:00Z",
  "importBatchId": "batch-20260929-001",
  "transaction": {
    "transactionId": "txn-000001",
    "clientId": "fb2c22ef-69fc-47de-b67f-5c1d0cfc795a",
    "accountId": "acc-000001",
    "type": "Deposit",
    "amount": "4250.00",
    "currency": "SGD",
    "transactionDate": "2026-09-28T00:30:00Z",
    "status": "Completed",
    "sourceFingerprint": "synthetic-employer-001"
  }
}
```

### Field rules

#### Envelope

- `schemaVersion`: required; exactly `"1"` for this contract.
- `eventId`: required stable unique identifier for delivery de-duplication.
- `occurredAt`: required time when the matched event was created.
- `importBatchId`: required stable identifier for the source import batch.
- `transaction`: required matched transaction payload.

#### Transaction

- `transactionId`: required; stable across retries/replays.
- `clientId`: required; transaction must not be emitted before this is resolved.
- `accountId`: required canonical account reference.
- `type`: required; `Deposit` or `Withdrawal`.
- `amount`: required non-negative decimal string with at most two fraction digits.
- `currency`: required uppercase ISO 4217 code; MVP expects `SGD` unless a currency policy is accepted.
- `transactionDate`: required source transaction time.
- `status`: required; `Completed`, `Pending`, or `Failed`.
- `sourceFingerprint`: optional opaque stable synthetic source reference. It supports recurring-credit grouping without exposing free-text remitter details.

### Producer guarantees requested

- Publish only after the transaction and matching result are durable.
- Never publish `Unmatched` or `Rejected` records through this contract.
- Delivery may be at least once.
- Preserve `eventId` and `transactionId` across retries.
- Document whether ordering is guaranteed; health-service logic must not require it.
- Publish updates/corrections as explicit versioned events rather than silently changing old messages.
- Provide six months of synthetic replay data.
- Notify consumers before incompatible changes.

### Consumer guarantees

- De-duplicate by `eventId`.
- Enforce unique transaction/version handling.
- Reject unsupported schema versions.
- Validate all fields before persistence.
- Never treat Pending as completed activity/net flow.
- Record invalid/unsupported input without creating signals/tasks.
- Store only the minimum read projection required by the selected rules.

### Matched transaction history API proposal

The selected proposal is a private pull API. It uses the same stable transaction IDs as ingestion and permits the consumer to request only the client's required time window.

```text
GET /internal/v1/transactions
    ?clientId={clientId}
    &from={inclusive-utc}
    &to={exclusive-utc}
    &cursor={opaque-cursor}
    &limit={bounded-limit}
```

Proposed response:

```json
{
  "schemaVersion": "1",
  "items": [
    {
      "transactionId": "synthetic-3b71d6a4c829a1051e8d9fc2b1437a60",
      "clientId": "fb2c22ef-69fc-47de-b67f-5c1d0cfc795a",
      "accountId": "e3351530-f5c1-4f64-aedc-3c97d9f76816",
      "type": "Deposit",
      "amount": "4250.00",
      "currency": "SGD",
      "transactionDate": "2026-09-28T05:00:00Z",
      "status": "Completed",
      "activityKind": "Automated",
      "sourceFingerprint": "synthetic-employer-a1b2c3d4e5f6"
    }
  ],
  "nextCursor": null
}
```

`activityKind` is exactly `CustomerInitiated`, `Automated`, or `Unknown`. `sourceFingerprint` is always present in the response and is either a stable source identifier or `null`. `type` and `status` retain the transaction-service values (`Deposit`/`Withdrawal` and `Completed`/`Pending`/`Failed`).

The transaction owner selected this private pull API for the first implementation. The receiver accepts Cognito client-credentials tokens with the `crm-transactions/read` scope; the Client Health Monitor uses the dedicated read-only app client. The endpoint requires inclusive `from` and exclusive `to` RFC3339 timestamps, caps each page at 500 rows, and returns only matched transactions with non-null CRM client and account IDs. Descriptions and import/reconciliation fields are not part of this response. A later human-owned ADR and cross-owner review must accept this proposed contract before production use.

## Contract B: Client Assignment V1

### Purpose

Resolve the assigned Agent without reading the client-profile database or accepting a caller-controlled agent identifier.

Proposed endpoint:

```text
GET /internal/v1/clients/{clientId}/assignment
```

Example response:

```json
{
  "schemaVersion": "1",
  "clientId": "fb2c22ef-69fc-47de-b67f-5c1d0cfc795a",
  "assignedAgentSubject": "opaque-cognito-subject",
  "active": true,
  "assignmentVersion": 3,
  "updatedAt": "2026-09-29T03:00:00Z"
}
```

Field rules:

- `clientId`: matches the requested canonical client.
- `assignedAgentSubject`: opaque Cognito subject; never assume UUID formatting.
- `active`: whether the client relationship may receive tasks.
- `assignmentVersion`: monotonically increasing assignment revision.
- `updatedAt`: assignment revision timestamp.

Expected errors:

- `404`: client does not exist.
- `409`: client exists but assignment is temporarily unresolved.
- `410`: client relationship is no longer active, if the owner accepts this representation.
- `503`: assignment dependency unavailable.

Security:

- Requires an accepted machine-to-machine authentication method.
- Must not accept an agent ID supplied by an end user.
- Health service stores the assignment snapshot used for each task.
- A future assignment-change event may replace repeated lookups.

## Contract C: ClientHealthFinding V1

### Purpose

This is the internal boundary enabling Garvit's detection stream and Swayam's task stream to work independently.

The detection stream produces it. The task stream consumes it through an in-process Java port inside the same Client Health Monitor microservice. It is not an external public API, HTTP call, or queue message.

Decision status:

- Approved by contract owner: Garvit, 2026-09-29
- Consumer acknowledgement: Swayam to confirm before SF05 implementation

Planned machine-readable artifacts, delivered through later SF00 PRs:

- `docs/api/schemas/client-health-finding-v1.schema.json`
- `tests/fixtures/client-health-monitor/client-health-finding-v1.valid.json`
- invalid fixtures covering empty signals, caller-controlled Agent ID, and mismatched evidence shape
- Java contract records under `services/client-health-monitor-service/src/main/java/bank/clienthealth/contract`

### Finding granularity

One finding represents:

```text
one client + one analysis run + every signal qualifying in that run
```

For example, if one analysis run detects recurring-credit interruption and activity decline for the same client, both signals are placed in one finding. The task stream creates one client-level task or attaches the new evidence to that client's existing `OPEN`/`IN_REVIEW` task.

A finding is immutable after production. A later analysis run creates a new finding with its own `findingId`.

### In-process port

Proposed Java boundary:

```java
public interface RelationshipTaskPort {
    TaskUpsertResult upsertFinding(ClientHealthFinding finding);
}
```

The detection stream depends only on this port. Swayam's task stream provides the implementation. No task repository, HTTP controller, SQS client, or Bedrock implementation is visible to the detection stream.

Example:

```json
{
  "schemaVersion": "1",
  "findingId": "finding-000001",
  "clientId": "fb2c22ef-69fc-47de-b67f-5c1d0cfc795a",
  "analysisRunId": "run-000001",
  "rulesetVersion": "relationship-health-v1",
  "analysisWindow": {
    "start": "2026-05-31T00:00:00Z",
    "end": "2026-09-28T00:00:00Z"
  },
  "signals": [
    {
      "signalId": "signal-000001",
      "accountId": "acc-000001",
      "type": "RECURRING_CREDIT_INTERRUPTION",
      "ruleVersion": "recurring-credit-v1",
      "severity": "MEDIUM",
      "window": {
        "baselineStart": "2026-05-31T00:00:00Z",
        "baselineEnd": "2026-08-29T00:00:00Z",
        "comparisonStart": "2026-08-29T00:00:00Z",
        "comparisonEnd": "2026-09-28T00:00:00Z"
      },
      "evidence": {
        "expectedCadenceDays": 30,
        "previousOccurrenceCount": 3,
        "daysSinceLastOccurrence": 67
      },
      "fingerprint": "sha256-hex"
    }
  ],
  "priorityInputs": {
    "highestSeverity": "MEDIUM",
    "signalCount": 1
  },
  "correlationId": "20ccf613-b37e-420f-8e9c-cd22ac21571b",
  "detectedAt": "2026-09-29T04:05:00Z"
}
```

### Finding rules

- `findingId`: stable idempotency identifier for one produced finding.
- `clientId`: required; task is client-level.
- `analysisRunId`: links processing evidence.
- `rulesetVersion`: exact ruleset used.
- `analysisWindow`: total immutable period covered by the analysis run.
- `signals`: non-empty immutable signal evidence list.
- each signal `window`: immutable baseline/comparison boundaries used by that specific rule.
- each signal `severity`: final deterministic severity calculated by the detection stream.
- `priorityInputs`: deterministic summary facts only; task stream uses `highestSeverity` as task priority and must not rerun rules.
- `correlationId`: shared across analysis, task, explanation, and audit.
- `detectedAt`: UTC creation time.

`ClientHealthFinding` deliberately does not contain an Agent ID. The task stream resolves the current assignment through Contract B before creating/updating a task.

### Idempotency and update behaviour

- `findingId` is the idempotency key for retrying one complete finding.
- each signal `fingerprint` is the evidence de-duplication key.
- reprocessing the same `findingId` returns the previous task-upsert outcome.
- a new finding for a client with an active task attaches only previously unseen signal fingerprints.
- a new finding must not overwrite or mutate prior evidence.
- unsupported major schema versions are rejected rather than guessed.
- unknown signal types are rejected until a compatible consumer version is deployed.

### Signal types

```text
RECURRING_CREDIT_INTERRUPTION
TRANSACTION_ACTIVITY_DECLINE
SUSTAINED_NET_OUTFLOW
```

### Severity values

Proposed:

```text
LOW
MEDIUM
HIGH
```

Severity thresholds remain a ruleset decision. They must never imply fraud, hardship, or regulatory risk.

### Evidence schemas

#### Recurring credit interruption

```json
{
  "expectedCadenceDays": 30,
  "previousOccurrenceCount": 3,
  "daysSinceLastOccurrence": 67
}
```

#### Transaction activity decline

```json
{
  "baselineMonthlyAverageCount": 24,
  "comparisonCount": 8,
  "changePercent": "-66.67"
}
```

#### Sustained net outflow

```json
{
  "periodCount": 2,
  "totalDeposits": "2000.00",
  "totalWithdrawals": "6500.00",
  "netFlow": "-4500.00",
  "currency": "SGD",
  "changeFromBaselinePercent": "-40.00"
}
```

The final evidence fields change only through coordinated contract review and fixtures.

Exact aggregate amounts may remain inside this authenticated internal contract and secured task/evidence storage because Agents need factual evidence. The explanation adapter must remove exact amounts and every identifier before constructing the Bedrock request unless a later approved decision explicitly permits them.

Garvit considers the three evidence schemas sufficient for V1. Swayam must confirm they are sufficient for task persistence, API responses, and explanation attachment before starting SF05.

## Contract D: Relationship Task API V1

### Base path

```text
/api/relationship-health/tasks
```

### Read operations

```text
GET /api/relationship-health/tasks
GET /api/relationship-health/tasks/{taskId}
GET /api/relationship-health/tasks/{taskId}/history
```

Authorization:

- Agent sees only tasks whose assignment snapshot matches the verified subject and current ownership policy.
- Admin may read across agents using bounded filters.
- Responses expose derived evidence, not raw transaction history.

### Agent actions

```text
POST /api/relationship-health/tasks/{taskId}/accept
POST /api/relationship-health/tasks/{taskId}/complete
POST /api/relationship-health/tasks/{taskId}/dismiss
```

Example accept:

```json
{
  "expectedVersion": 1
}
```

Example complete:

```json
{
  "expectedVersion": 2,
  "outcomeCode": "CLIENT_REVIEW_COMPLETED",
  "note": "Optional bounded synthetic note"
}
```

Example dismiss:

```json
{
  "expectedVersion": 1,
  "reasonCode": "EXPECTED_CLIENT_ACTIVITY",
  "note": "Optional bounded synthetic note"
}
```

### Task response

```json
{
  "schemaVersion": "1",
  "taskId": "task-000001",
  "clientId": "fb2c22ef-69fc-47de-b67f-5c1d0cfc795a",
  "assignedAgentSubject": "opaque-cognito-subject",
  "status": "OPEN",
  "severity": "MEDIUM",
  "explanation": {
    "text": "Review suggested because an established recurring credit was not observed in the expected period.",
    "source": "TEMPLATE_FALLBACK",
    "generatedAt": "2026-09-29T04:05:05Z"
  },
  "evidence": [
    {
      "type": "RECURRING_CREDIT_INTERRUPTION",
      "accountId": "acc-000001",
      "ruleVersion": "recurring-credit-v1",
      "severity": "MEDIUM"
    }
  ],
  "version": 1,
  "createdAt": "2026-09-29T04:05:05Z",
  "updatedAt": "2026-09-29T04:05:05Z"
}
```

### Status values

```text
OPEN
IN_REVIEW
COMPLETED
DISMISSED
```

Allowed transitions:

```text
OPEN -> IN_REVIEW
OPEN -> DISMISSED
IN_REVIEW -> COMPLETED
IN_REVIEW -> DISMISSED
```

### API errors

- `400`: malformed request/unsupported reason code.
- `401`: missing/invalid access token.
- `403`: authenticated but not allowed.
- `404`: task not found or hidden by ownership policy.
- `409`: invalid transition or optimistic-lock conflict.
- `422`: semantically invalid bounded note/outcome data.
- `429`: rate limit.
- `503`: required dependency unavailable.

Task creation/evidence attachment are internal operations, not public endpoints.

Post-MVP endpoints such as reopen/reassign/snooze are intentionally absent.

## Contract E: Explanation Request/Result V1

### Provider-independent request

```json
{
  "schemaVersion": "1",
  "promptTemplateVersion": "relationship-health-explanation-v1",
  "facts": [
    {
      "evidenceCode": "RECURRING_CREDIT_INTERRUPTION",
      "observationWindowDays": 120,
      "expectedCadenceDays": 30,
      "daysSinceLastOccurrence": 67,
      "previousOccurrenceCount": 3
    }
  ],
  "constraints": {
    "maximumCharacters": 400,
    "humanReviewRequired": true
  }
}
```

This object must not contain client/account/transaction/agent IDs, names, raw descriptions, exact account numbers, authentication tokens, or audit data.

### Result

```json
{
  "schemaVersion": "1",
  "summary": "This relationship may require review because an established recurring credit has not appeared within its expected period.",
  "suggestedReviewStep": "Review the verified account activity before deciding whether client contact is appropriate.",
  "evidenceCodes": [
    "RECURRING_CREDIT_INTERRUPTION"
  ],
  "disclaimer": "Human review required"
}
```

Validation:

- every evidence code exists in the request;
- no additional number/fact is introduced;
- all required fields are present;
- text and character limits are enforced;
- prohibited claims are rejected;
- invalid output causes template fallback.

## Contract F: Auditor SQS Event V1

### Current transport

Proposed queue target:

```text
audit-insert
```

The transport exists, but this feature's service type/action/payload require Auditor-owner approval and implementation.

### Proposed envelope

```json
{
  "event_id": "c3db3d8e-b5c1-4622-8baf-4f79490bd4d6",
  "event_type": "RELATIONSHIP_HEALTH",
  "action": "TASK_CREATED",
  "source_service": "RELATIONSHIP_HEALTH_SERVICE",
  "actor_id": "SYSTEM",
  "payload": {
    "schema_version": "1",
    "entity_type": "RELATIONSHIP_TASK",
    "entity_id": "task-000001",
    "occurred_at": "2026-09-29T04:05:05Z",
    "correlation_id": "20ccf613-b37e-420f-8e9c-cd22ac21571b",
    "client_ref": "opaque-or-hashed-reference",
    "before": null,
    "after": {
      "status": "OPEN"
    }
  }
}
```

Proposed actions:

```text
ANALYSIS_RUN_STARTED
ANALYSIS_RUN_COMPLETED
ANALYSIS_RUN_FAILED
SIGNAL_DETECTED
SIGNAL_SUPPRESSED
TASK_CREATED
TASK_EVIDENCE_ADDED
TASK_ACCEPTED
TASK_COMPLETED
TASK_DISMISSED
EXPLANATION_GENERATED
EXPLANATION_FALLBACK_USED
OUTBOX_DELIVERY_RECOVERED
```

Requirements:

- `event_id` is stable across retries.
- Business change and outbox event commit atomically.
- Producer sends through IAM-authorized SQS access.
- Rejected events remain diagnosable through the Auditor DLQ.
- Payload contains no raw transactions or unnecessary PII.
- Auditor must explicitly allow the service/event/payload.

## Contract versioning

Compatible V1 changes:

- adding optional fields with safe defaults;
- adding new reason/outcome codes with consumer fallback;
- adding a new signal type only after consumers tolerate unknown values; and
- clarifying documentation without changing meaning.

Breaking changes requiring a new major version:

- removing/renaming required fields;
- changing identifier/amount/time semantics;
- changing task transition meaning;
- changing required authorization behaviour; or
- changing idempotency guarantees.

Every contract change requires:

1. feature-plan task/owner update;
2. reviewer approval;
3. fixture/contract-test update;
4. compatibility and migration notes; and
5. an ADR when architecture/security/operations are affected.

## SF00 review checklist

- [ ] Garvit approves transaction/evidence semantics.
- [ ] Swayam approves `ClientHealthFinding`, task API, lifecycle, and explanation attachment needs.
- [ ] Transaction owner confirms matched transaction fields and replay feasibility.
- [ ] Client-profile owner confirms assignment contract feasibility.
- [ ] Auditor owner confirms service type, event/action allowlist, and payload shape.
- [ ] Security/platform owner confirms machine authentication direction.
- [ ] No implementation treats these proposed contracts as accepted before review.

