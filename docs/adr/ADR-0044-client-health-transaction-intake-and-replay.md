# ADR-0044: Client health transaction intake and historical replay

- Status: Accepted
- Decision date/time: 2026-10-16 18:59 +08:00 (Asia/Singapore)
- Decision owner(s): Garvit
- Drafted/assisted by AI: Yes; Cursor
- Feature/branch: `task/client-health-monitor/transaction-intake`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

The Client Health Monitor needs matched synthetic transaction activity for
deterministic relationship-health rules and a six-month historical replay.
Transaction-service now stores resolved account and client IDs for matched
imports and has a private cursor-paged transaction list. That list supports the
admin UI, is not authenticated at transaction-service, has no bounded date
window, and does not provide the stable source fingerprint required to recognise
recurring credits.

The feature must not read transaction-service's database, export free-text
descriptions, trust the network, or analyse a partial replay after a failed page.

## Decision Drivers

- Reuse the current transaction-service and its keyset-paging model.
- Preserve service ownership and one database per business area.
- Apply Zero Trust to the daily scheduled analysis task.
- Make six-month replay deterministic, bounded, resumable and idempotent.
- Provide stable recurring-credit grouping without exposing descriptions.
- Avoid another queue, replay store and delivery path for a daily analysis.

## Options Considered

### Option A: Pull a bounded internal replay API

Transaction-service exposes a dedicated service-authenticated endpoint with
date bounds and opaque cursor paging.

- Pros: reuses current storage and paging; naturally supports replay; no new
  broker; the daily task receives the current status of pending transactions.
- Cons: the analysis depends on transaction-service during a run; the provider
  needs a small contract extension and synthetic-history support.

### Option B: Publish matched transactions to SQS

Transaction ingest publishes every matched transaction and maintains a separate
replay mechanism.

- Pros: low-latency incremental delivery and loose runtime coupling.
- Cons: a second durable delivery and replay path, more idempotency state,
  additional IAM/operations/cost, and no product need for sub-daily detection.

### Option C: Read transaction-service's database

- Pros: simplest query.
- Cons: violates service ownership, database isolation and the feature plan.

## Decision

Choose **Option A**.

1. The health analysis task calls transaction-service through the internal ALB
   using a Cognito client-credentials token as required by ADR-0025 and
   ADR-0034. A dedicated least-privilege scope such as
   `crm-transactions/health-read` is used. User tokens are not accepted.
2. Transaction-service provides a dedicated versioned endpoint:

   ```text
   GET /internal/v1/transactions
       ?from={inclusive-utc}
       &to={exclusive-utc}
       &cursor={opaque-cursor}
       &limit={bounded-limit}
   ```

   It is separate from the admin `GET /api/transactions` route in ADR-0043.
3. Only rows with `import_status = Matched` and non-null account and client IDs
   are returned. Results use stable keyset ordering and an opaque cursor.
4. Each item contains only:
   - transaction ID;
   - client and account IDs;
   - Deposit/Withdrawal type;
   - decimal-string amount;
   - currency;
   - transaction timestamp;
   - Completed/Pending/Failed status; and
   - an opaque stable synthetic `sourceFingerprint`, when applicable.
5. The synthetic producer supplies `sourceFingerprint`; transaction-service
   stores and returns it. It is never inferred by hashing or exporting the
   free-text description. The transaction owners must approve and implement
   this provider-side extension in their own task/PR.
6. Synthetic fixtures cover at least six months and include reproducible
   recurring-credit, activity-decline, net-outflow and negative/control cases.
7. Initial replay requests six months. Daily analysis requests only its
   configured analysis window. Both follow every cursor until the source marks
   the final page.
8. The SF01 consumer validates every page and item before publishing a complete
   intake result to SF02. If any page is unavailable or invalid, the run fails
   and no partial result is exposed for analysis.
9. Transactions are de-duplicated by transaction ID. Re-reading an identical
   row is idempotent. Across runs, only a stored Pending status may advance to
   Completed or Failed, matching ADR-0024; conflicting immutable fields fail
   closed and are recorded for diagnosis.
10. Unsupported contract versions, invalid cursors, out-of-window rows,
    unmatched rows and malformed amounts/currencies/timestamps are rejected.
    Raw descriptions are neither requested nor stored by the health service.
11. The provider-side endpoint, schema, authentication scope and producer
    changes require coordination and approval from the Feature 3 owners. This
    ADR authorises the health-service consumer boundary; it does not assign or
    silently modify another team's shared files.

## Rationale

The rules run once per day, so an event stream provides little product value.
A bounded pull API gives replay and current-state reads through one contract,
while preserving transaction-service ownership. The all-or-nothing SF01 result
prevents incomplete pages from creating false health signals.

## Consequences

### Positive

- Six-month replay and daily reads use one versioned contract.
- No new broker or replay store is required.
- The scheduled task has a least-privilege machine identity.
- Recurring-credit grouping does not depend on sensitive free text.

### Negative / Trade-offs

- Analysis waits for transaction-service and fails closed on an incomplete run.
- Transaction-service and the synthetic producer require coordinated changes.
- A full six-month replay can span many pages and must remain bounded.
- SF01 cannot be considered complete until the provider extension and
  end-to-end replay are verified.

## Security & Privacy Impact

- Service tokens and TLS are required; network location is not trusted.
- The scope grants read-only access to matched synthetic activity.
- No names, contact details, descriptions, authentication data or unmatched
  records cross the boundary.
- The health service stores only the minimum projection needed by its rules.

## Cost Impact

The endpoint reuses transaction-service, its database and the internal ALB.
Cognito machine-token requests and one bounded daily read add negligible cost.
No queue or always-on component is introduced, preserving the USD 300 limit.

## Operational Impact

- Add provider and consumer metrics for page count, row count, duration,
  rejected rows and failed/incomplete runs.
- Alarm on repeated replay failures before the daily analysis window.
- Cache and refresh the service token before expiry; keep its secret in Secrets
  Manager, never source control or logs.
- Replay can be retried safely because rows and cursors are deterministic and
  the consumer is idempotent.

## Validation

- Contract tests accept valid pages and reject every invalid field/version.
- Repeated pages and overlapping windows produce one logical transaction.
- Pending may advance to Completed/Failed; other mutations fail closed.
- A failure on any page exposes no partial completed intake result.
- Six-month synthetic replay includes all three positive signal scenarios and
  negative controls.
- Wrong-scope, user and expired tokens are rejected.
- An end-to-end replay reads only matched rows and exports no descriptions.

## References

- [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md)
- [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md)
- [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md)
- [ADR-0037](ADR-0037-transactions-database-and-account-linking.md)
- [ADR-0043](ADR-0043-client-and-transaction-list-endpoints.md)
- [`client-health-monitor_plan.md`](../feature-plans/client-health-monitor_plan.md)
- [`client-health-monitor-contracts.md`](../api/client-health-monitor-contracts.md)
