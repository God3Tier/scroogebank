# ADR-0051: Explicit activity classification for Client Health

- Status: Accepted
- Decision date/time: 2026-10-21 18:43 +08:00 (Asia/Singapore)
- Decision owner(s): Garvit
- Drafted/assisted by AI: Yes; Cursor
- Feature/branch: `task/client-health-monitor/activity-projection`
- Related issue/PR: TBD
- Amends: ADR-0044 transaction item contract
- Supersedes: N/A
- Superseded by: N/A

## Context

RH-SF02 must compare completed customer-initiated activity with the client's own
baseline while excluding automated and system-generated entries. ADR-0044's
transaction item contains a recurring-source fingerprint, but fingerprint
presence is not a reliable general activity classification: an automated entry
may have no recurring source, and a customer transaction may carry other source
metadata. Descriptions cannot cross the boundary because they are unnecessary
free text.

## Decision Drivers

- Deterministic and explainable activity counts
- No inference from sensitive or inconsistent descriptions
- Fail-closed treatment of ambiguous provider data
- One classification shared by the mock and future Feature 3 provider

## Options Considered

### Explicit activity kind

The provider labels each transaction `CustomerInitiated`, `Automated`, or
`Unknown`.

- Pros: deterministic, testable and independent of descriptions.
- Cons: Feature 3 must populate one additional contract field.

### Infer activity kind from source fingerprint

- Pros: no field change.
- Cons: fingerprint presence only identifies a stable synthetic source; it does
  not prove whether every transaction was customer-initiated or automated.

## Decision

Use an explicit required `activityKind` field in the V1 replay contract:

```text
CustomerInitiated | Automated | Unknown
```

1. RH-SF02 counts only `CustomerInitiated` completed transactions for the
   activity-decline baseline and comparison.
2. `Automated` and `Unknown` are excluded from activity counts. `Unknown` is
   surfaced as insufficient classification rather than guessed.
3. Completed deposits and withdrawals of any activity kind may contribute to
   currency-safe net-flow aggregates; their classification remains visible in
   evidence.
4. Recurring-credit grouping still requires a non-null stable
   `sourceFingerprint`; it does not derive identity from `activityKind`.
5. Pending and Failed rows contribute to no SF02 aggregate.
6. The contract mock and Feature 3 provider use the same enum. Descriptions are
   not requested, stored, logged, or used to infer the value.

Because the production Feature 3 endpoint has not yet been implemented, adding
this required field does not break a deployed consumer. Joseph Yau and the
Feature 3 implementation owner must include it when delivering ADR-0044.

## Consequences

### Positive

- Activity decline is reproducible and auditable.
- Ambiguous data fails closed.
- The provider can classify synthetic rows without exporting free text.

### Negative / Trade-offs

- Feature 3 has one more required field to generate, persist and return.
- Unknown rows reduce activity coverage and require a visible sufficiency flag.

## Security & Privacy Impact

The enum carries no PII. It replaces, rather than supplements, any temptation
to send descriptions or merchant/remitter text to Client Health.

## Cost Impact

Negligible storage and payload overhead; no new AWS service or runtime.

## Operational Impact

Metrics record counts by activity kind and the number of Unknown rows excluded
from activity baselines. Contract version/schema failures stop the run before
analysis.

## Validation

- Contract schema rejects a missing or unsupported activity kind.
- Automated and Unknown completed rows do not increase activity counts.
- CustomerInitiated completed rows do.
- Classification does not affect recurring-source grouping.
- Net-flow summaries remain exact and currency-safe.

## References

- [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md)
- [ADR-0044](ADR-0044-client-health-transaction-intake-and-replay.md)
- [`client-health-monitor_plan.md`](../feature-plans/client-health-monitor_plan.md)
