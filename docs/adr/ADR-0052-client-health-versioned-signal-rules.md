# ADR-0052: Versioned deterministic Client Health signal rules

- Status: Accepted
- Decision date/time: 2026-10-21 19:10 +08:00 (Asia/Singapore)
- Decision owner(s): Garvit
- Drafted/assisted by AI: Yes; Cursor
- Feature/branch: `task/client-health-monitor/signal-rules`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

ADR-0034 requires deterministic, explainable and versioned relationship-health
rules. RH-SF02 supplies six months of completed matched activity, an explicit
activity classification, a 90-day baseline, and a 30-day comparison. RH-SF03
must now freeze initial project/demo thresholds without presenting them as
universal banking standards or fabricated probabilities.

## Decision Drivers

- Reproducible outcomes with exact evidence
- Important boundary and insufficient-history tests
- Thresholds suitable for labelled synthetic scenarios
- No demographic comparison, LLM detection, or pseudo-confidence percentage
- Conservative fail-closed handling of ambiguous data

## Decision

The initial ruleset is `relationship-health-v1`.

### Recurring-credit interruption

1. Group completed deposits by account and stable `sourceFingerprint`.
2. Require at least three prior observations.
3. Estimate cadence as the median whole-day interval between observations.
4. Require every amount to be within 20% of the median observed amount.
5. Grace is the larger of seven days or 25% of the estimated cadence.
6. Trigger when days since the latest observation exceed cadence plus grace.
7. Severity is based on missed cycles after grace:
   - `LOW`: one cycle;
   - `MEDIUM`: two cycles;
   - `HIGH`: three or more cycles.

The signal is called an income-like recurring-credit interruption, never
verified salary or financial distress.

### Transaction-activity decline

1. Count only completed `CustomerInitiated` transactions.
2. Require at least six transactions in the 90-day baseline and no `Unknown`
   classification in either window.
3. Normalise the baseline to a monthly average by dividing by three.
4. Trigger when the latest 30-day count is at least 50% lower.
5. Severity:
   - `LOW`: 50% to below 65%;
   - `MEDIUM`: 65% to below 80%;
   - `HIGH`: 80% or greater.

### Sustained net-outflow trend

1. Build six consecutive 30-day account periods ending at the analysis cut-off.
2. Require each of the latest two periods to have net flow at or below
   `-SGD 500.00`.
3. Across those two periods, require withdrawals to be at least 1.25 times
   deposits.
4. Severity by combined withdrawal/deposit ratio:
   - `LOW`: 1.25 to below 1.50;
   - `MEDIUM`: 1.50 to below 2.00;
   - `HIGH`: 2.00 or greater.
   - When deposits are zero and withdrawals satisfy materiality, severity is
     `HIGH`.
5. Evidence compares recent average net flow with the previous 90-day average;
   the percentage change is normalised by average baseline transaction volume
   so a zero baseline net flow does not cause division by zero.

### Shared behavior

- Pending and Failed transactions contribute to no rule.
- Mixed-currency input fails before rule evaluation; V1 is SGD-only.
- A rule returns zero or more typed account-level outcomes with exact windows,
  severity, rule version, and existing `ClientHealthFinding` evidence records.
- Rules return no confidence percentage. Exact evidence and severity are the
  explanation.
- RH-SF04, not the rules, creates signal IDs/fingerprints, combines outcomes,
  applies cooldown/caps, and creates the final finding.
- Thresholds change only through a new ruleset version and labelled fixture
  updates; historical outcomes retain the version used.

## Rationale

The thresholds produce all three signals in the reviewed synthetic scenarios
while suppressing sparse or ambiguous activity. Relative activity and flow
checks compare each client with their own history. Materiality prevents tiny
negative balances from being labelled as sustained outflow.

## Consequences

### Positive

- Every outcome is deterministic, versioned and independently testable.
- No model or subjective probability controls detection.
- SF04 receives stable typed outcomes without recalculating rules.

### Negative / Trade-offs

- Thresholds are project defaults and require evaluation before any real use.
- Median cadence with only three observations has limited evidence, so the UI
  must show the occurrence count.
- SGD-only evaluation suppresses mixed-currency clients until a conversion
  policy is accepted.

## Security & Privacy Impact

Rules consume synthetic identifiers and derived aggregates only. They do not
use names, descriptions, demographics, contact details, or Bedrock.

## Cost Impact

In-memory deterministic evaluation adds no AWS service and negligible compute.

## Operational Impact

Metrics count evaluated, triggered and insufficient outcomes by rule version.
Rule configuration is immutable within one analysis run.

## Validation

- Positive, exact-boundary and just-below-boundary fixtures for each rule.
- Healthy controls produce no outcomes.
- Insufficient history and Unknown activity produce no decline signal.
- Amount variation suppresses unstable recurring streams.
- Only two materially negative consecutive periods trigger net outflow.
- The same inputs and ruleset always produce identical outcomes.

## References

- [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md)
- [ADR-0051](ADR-0051-client-health-explicit-activity-classification.md)
- [`client-health-monitor_plan.md`](../feature-plans/client-health-monitor_plan.md)
