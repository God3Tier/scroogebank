# Client Health offline rule evaluation

## Scope

This evaluation validates the deterministic `relationship-health-v1` rules
accepted in ADR-0052 against repository-owned synthetic labelled scenarios. It
does not estimate real-client accuracy, attrition, financial distress, fraud,
credit risk, or production performance.

Evaluation date: 2026-10-21  
Ruleset: `relationship-health-v1`  
Command:

```text
mvn -pl services/client-health-monitor-service -am test
```

## Labelled scenarios

1. Combined positive history:
   - recurring-credit interruption: HIGH;
   - customer-activity decline: MEDIUM;
   - sustained net outflow: HIGH;
   - healthy control client: no signal.
2. Healthy recurring/control history: no signal.
3. Exact low-severity boundaries: all three LOW signals.
4. Just below thresholds:
   - amount variation above 20%;
   - activity baseline below six transactions;
   - each recent net-outflow period less negative than SGD 500;
   - no signal.
5. Unknown activity classification: decline rule fails closed.
6. Sparse history: decline rule is suppressed.

## Results

- Scenarios: 6
- Deterministic repeat: true
- All labelled scenarios passed: true
- Expected signals: 6
- Predicted signals: 6
- Labelled true positives: 6
- Labelled false positives: 0
- Labelled false negatives: 0
- Severity matches: 6
- Precision on this fixed synthetic set: 1.00
- Labelled positive-scenario coverage on this fixed synthetic set: 1.00

Per rule:

- `RECURRING_CREDIT_INTERRUPTION`: 2 expected, 2 detected, 0 FP, 0 FN,
  2 severity matches.
- `TRANSACTION_ACTIVITY_DECLINE`: 2 expected, 2 detected, 0 FP, 0 FN,
  2 severity matches.
- `SUSTAINED_NET_OUTFLOW`: 2 expected, 2 detected, 0 FP, 0 FN,
  2 severity matches.

The automated harness also runs every scenario twice and requires byte-equivalent
rule outcomes.

## Interpretation and limits

- Passing results prove implementation agreement with the accepted synthetic
  labels and boundaries; they do not prove generalisation to unseen real data.
- Scenarios are intentionally small and deterministic.
- The data is synthetic and SGD-only.
- Feature 3 production replay, health-db persistence, task admission, Bedrock,
  audit delivery and deployed performance are outside this evaluation.
- Threshold changes require a new ruleset version and corresponding labelled
  fixture updates.

## Evidence

- Harness: `bank.clienthealth.rules.evaluation.RuleEvaluationHarness`
- Scenarios: `bank.clienthealth.rules.evaluation.RuleEvaluationScenarios`
- Automated assertions: `RuleEvaluationHarnessTest`
- Rule boundary tests: `RelationshipHealthRuleEngineTest`
- Decision: [`ADR-0052`](../adr/ADR-0052-client-health-versioned-signal-rules.md)
