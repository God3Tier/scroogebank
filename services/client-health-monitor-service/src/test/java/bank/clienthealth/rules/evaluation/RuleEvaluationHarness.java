package bank.clienthealth.rules.evaluation;

import bank.clienthealth.activity.application.ActivityBaselineService.BaselineResult;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.rules.DetectedSignal;
import bank.clienthealth.rules.RelationshipHealthRuleEngine;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Offline labelled-scenario evaluator; this is not a real-client accuracy claim. */
final class RuleEvaluationHarness {
    private final RelationshipHealthRuleEngine engine;

    RuleEvaluationHarness(RelationshipHealthRuleEngine engine) {
        this.engine = Objects.requireNonNull(engine);
    }

    EvaluationReport evaluate(List<LabelledScenario> scenarios) {
        MutableMetrics overall = new MutableMetrics();
        EnumMap<SignalType, MutableMetrics> byRule = new EnumMap<>(SignalType.class);
        for (SignalType type : SignalType.values()) {
            byRule.put(type, new MutableMetrics());
        }
        List<ScenarioResult> results = new ArrayList<>();
        boolean deterministic = true;

        for (LabelledScenario scenario : scenarios) {
            var first = engine.evaluate(scenario.baseline());
            var repeated = engine.evaluate(scenario.baseline());
            deterministic &= first.equals(repeated);
            Map<SignalKey, DetectedSignal> actual = index(first.outcomes());
            Map<SignalKey, ExpectedSignal> expected = indexExpected(scenario.expected());
            Set<SignalKey> keys = new java.util.HashSet<>(actual.keySet());
            keys.addAll(expected.keySet());
            MutableMetrics scenarioMetrics = new MutableMetrics();

            for (SignalKey key : keys) {
                DetectedSignal prediction = actual.get(key);
                ExpectedSignal label = expected.get(key);
                update(overall, prediction, label);
                update(byRule.get(key.type()), prediction, label);
                update(scenarioMetrics, prediction, label);
            }
            results.add(new ScenarioResult(
                    scenario.name(), scenarioMetrics.freeze(),
                    scenarioMetrics.falsePositives == 0 && scenarioMetrics.falseNegatives == 0
                            && scenarioMetrics.truePositives == scenarioMetrics.severityCorrect));
        }

        Map<SignalType, Metrics> frozenRules = new EnumMap<>(SignalType.class);
        byRule.forEach((type, metrics) -> frozenRules.put(type, metrics.freeze()));
        return new EvaluationReport(
                scenarios.size(), deterministic, overall.freeze(), frozenRules, results);
    }

    private static Map<SignalKey, DetectedSignal> index(List<DetectedSignal> signals) {
        Map<SignalKey, DetectedSignal> indexed = new LinkedHashMap<>();
        for (DetectedSignal signal : signals) {
            SignalKey key = new SignalKey(signal.clientId(), signal.accountId(), signal.type());
            if (indexed.putIfAbsent(key, signal) != null) {
                throw new IllegalStateException("duplicate rule outcome: " + key);
            }
        }
        return indexed;
    }

    private static Map<SignalKey, ExpectedSignal> indexExpected(List<ExpectedSignal> labels) {
        Map<SignalKey, ExpectedSignal> indexed = new LinkedHashMap<>();
        for (ExpectedSignal label : labels) {
            if (indexed.putIfAbsent(label.key(), label) != null) {
                throw new IllegalArgumentException("duplicate expected signal: " + label.key());
            }
        }
        return indexed;
    }

    private static void update(
            MutableMetrics metrics, DetectedSignal prediction, ExpectedSignal label
    ) {
        if (prediction != null) metrics.predicted++;
        if (label != null) metrics.expected++;
        if (prediction != null && label != null) {
            metrics.truePositives++;
            if (prediction.severity() == label.severity()) metrics.severityCorrect++;
        } else if (prediction != null) {
            metrics.falsePositives++;
        } else {
            metrics.falseNegatives++;
        }
    }

    record LabelledScenario(
            String name,
            BaselineResult baseline,
            List<ExpectedSignal> expected
    ) {
        LabelledScenario {
            name = Objects.requireNonNull(name);
            baseline = Objects.requireNonNull(baseline);
            expected = List.copyOf(expected);
        }
    }

    record ExpectedSignal(SignalKey key, Severity severity) {
        ExpectedSignal {
            key = Objects.requireNonNull(key);
            severity = Objects.requireNonNull(severity);
        }
    }

    record SignalKey(String clientId, String accountId, SignalType type) { }

    record Metrics(
            int expected,
            int predicted,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            int severityCorrect
    ) {
        double precision() {
            return predicted == 0 ? 1.0 : (double) truePositives / predicted;
        }

        double labelledCoverage() {
            return expected == 0 ? 1.0 : (double) truePositives / expected;
        }
    }

    record ScenarioResult(String name, Metrics metrics, boolean passed) { }

    record EvaluationReport(
            int scenarioCount,
            boolean deterministic,
            Metrics overall,
            Map<SignalType, Metrics> byRule,
            List<ScenarioResult> scenarios
    ) {
        EvaluationReport {
            byRule = Map.copyOf(byRule);
            scenarios = List.copyOf(scenarios);
        }

        boolean allScenariosPassed() {
            return deterministic && scenarios.stream().allMatch(ScenarioResult::passed);
        }
    }

    private static final class MutableMetrics {
        int expected;
        int predicted;
        int truePositives;
        int falsePositives;
        int falseNegatives;
        int severityCorrect;

        Metrics freeze() {
            return new Metrics(
                    expected, predicted, truePositives,
                    falsePositives, falseNegatives, severityCorrect);
        }
    }
}
