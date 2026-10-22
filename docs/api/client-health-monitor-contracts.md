# Client Health Monitor — Proposed Contracts

## Status and ownership

- Status: SF00 internal finding contract implemented; RH-SF01 pull/replay direction accepted by ADR-0044; external provider extensions pending owner review
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

- the existing private list is an admin-UI contract rather than a
  service-authenticated, date-bounded replay contract;
- no stable source/counterparty fingerprint; and
- no six-month reproducible relationship-health history.

Matched imports now populate `account_id` and `client_id` through
account-service under ADR-0037. Transaction-service also exposes a private
cursor-paged admin list under ADR-0043. ADR-0044 requires a separate internal
replay endpoint with a least-privilege service token rather than reusing that
human-admin route.

### Client assignment

The client-profile service stores the creating/owning Cognito subject in `created_by_agent_id`, but exposes no machine-to-machine assignment contract.

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

Provide transaction facts only after durable import and successful
account/client matching. ADR-0044 selects a bounded paginated internal pull API
for initial replay and daily analysis. It does not select an event stream.

### Transaction field rules

- `transactionId`: required; stable across retries/replays.
- `clientId`: required; transaction must not be emitted before this is resolved.
- `accountId`: required canonical account reference.
- `type`: required; `Deposit` or `Withdrawal`.
- `amount`: required non-negative decimal string with at most two fraction digits.
- `currency`: required uppercase ISO 4217 code; MVP expects `SGD` unless a currency policy is accepted.
- `transactionDate`: required source transaction time.
- `status`: required; `Completed`, `Pending`, or `Failed`.
- `activityKind`: required under ADR-0051; `CustomerInitiated`, `Automated`, or
  `Unknown`. Unknown is excluded from activity counts rather than guessed.
- `sourceFingerprint`: optional opaque stable synthetic source reference. It supports recurring-credit grouping without exposing free-text remitter details.

### Provider guarantees requested

- Return only after the transaction and matching result are durable.
- Never return `Unmatched` or `Rejected` records through this contract.
- Preserve `transactionId` across repeated pages and replays.
- Use stable keyset ordering with opaque cursors.
- Apply the source rule that only Pending may advance to Completed or Failed.
- Provide six months of synthetic replay data.
- Notify consumers before incompatible changes.

### Consumer guarantees

- De-duplicate by `transactionId`.
- Enforce immutable fields and allow only Pending-to-terminal status changes
  across repeated runs.
- Reject unsupported schema versions.
- Validate all fields before persistence.
- Never treat Pending as completed activity/net flow.
- Record invalid/unsupported input without creating signals/tasks.
- Store only the minimum read projection required by the selected rules.

### Historical replay endpoint

Accepted consumer contract in ADR-0044; provider implementation and Feature 3
owner approval remain pending:

```text
GET /internal/v1/transactions
    ?from={inclusive-utc}
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
      "transactionId": "txn-000001",
      "clientId": "fb2c22ef-69fc-47de-b67f-5c1d0cfc795a",
      "accountId": "acc-000001",
      "type": "Deposit",
      "amount": "4250.00",
      "currency": "SGD",
      "transactionDate": "2026-09-28T00:30:00Z",
      "status": "Completed",
      "activityKind": "Automated",
      "sourceFingerprint": "synthetic-employer-001"
    }
  ],
  "nextCursor": null
}
```

The endpoint requires a scoped Cognito client-credentials token, inclusive
`from`, exclusive `to`, bounded `limit`, and an opaque provider cursor. The
consumer exposes no completed intake result unless every page validates and the
provider returns the final cursor.

Machine-readable consumer artifacts:

- `docs/api/schemas/transaction-replay-page-v1.schema.json`
- valid and unsafe provider fixtures under
  `tests/fixtures/client-health-monitor/transaction-replay-page-v1.*.json`
- Java records and validation under
  `bank.clienthealth.transaction.contract`

Until Feature 3 delivers the production endpoint, local analysis uses
`SyntheticTransactionReplaySource`. It follows the same paging contract and
provides six deterministic months covering interrupted recurring credit,
declining activity, sustained net outflow, healthy controls, and non-completed
rows. It is a replaceable adapter, not a second contract.

### RH-SF02 projection and baseline boundary

- A complete replay is applied atomically by transaction ID.
- Immutable-field changes fail closed; only Pending may advance to Completed or
  Failed.
- Applied replay windows are recorded so analysis cannot mistake absent
  coverage for zero activity.
- At 08:00 Asia/Singapore, SF02 exposes a 90-day baseline and non-overlapping
  30-day comparison per account plus an exact client roll-up.
- Six months remain available for recurring-credit series grouped by
  `sourceFingerprint`.
- Activity counts include only completed `CustomerInitiated` rows. Automated
  and Unknown rows are excluded and Unknown counts remain visible.
- Pending/Failed rows contribute to no aggregate. Mixed-currency windows fail
  closed; the MVP baseline currency is SGD.
- SF02 exposes exact summaries and sufficiency metadata; SF03 alone decides
  signal thresholds and severity.

### RH-SF03 deterministic rule boundary

- Ruleset `relationship-health-v1` is frozen in ADR-0052.
- Six rolling 30-day account summaries support sustained-period evaluation.
- Each rule returns zero or more typed account-level outcomes containing
  client/account IDs, signal type, rule version, severity, exact windows, and
  the matching `ClientHealthFinding` evidence record.
- Rules return no confidence percentage and perform no task creation,
  fingerprinting, cooldown, cap, audit, AI, or persistence behavior.
- RH-SF04 alone assigns signal IDs/fingerprints, combines all outcomes for one
  client/run, applies suppression, and invokes `RelationshipTaskPort`.

### RH-SF04 analysis and task-handoff boundary

- Daily run IDs are deterministic from analysis date and ruleset version.
- A renewable 60-minute lease prevents overlapping owners; an expired lease is
  recoverable.
- Finding/signal/correlation IDs are deterministic. Evidence fingerprints omit
  the run ID so overlapping runs de-duplicate the same rule/account/window.
- One immutable finding contains every detected signal for one client/run.
- `RelationshipTaskPort` returns `CREATED`, `EVIDENCE_ATTACHED`, `DUPLICATE`,
  `SUPPRESSED_COOLDOWN`, or `SUPPRESSED_DAILY_CAP`.
- SF05 resolves assignment and makes cooldown/cap admission atomic with task,
  history and outbox persistence under ADR-0053.
- SF04 advances its watermark only after every finding has an explicit outcome.
  Failure leaves the watermark unchanged; deterministic retries safely replay
  already-handled findings.

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

Queue target accepted in ADR-0020:

```text
audit-insert
```

The provider-independent Client Health envelope/factory and leased outbox relay
are implemented in RH-SF08. The feature's service type/action/payload still
require Auditor-owner approval before shared Auditor files or SQS adapters
change.

### Envelope

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
- `client_ref` is a one-way lowercase SHA-256 reference; raw client IDs, notes,
  descriptions, evidence amounts and transaction data are excluded.
- Before/after states contain only task status, severity and version.
- Delivery leases a bounded batch, continues after one failure, deletes only
  after gateway acceptance, and releases failures with bounded redacted retry
  categories/backoff.
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

