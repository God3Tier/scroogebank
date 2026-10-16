package bank.alert.ai.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AiBenchmarkEvaluator {
    private final ClassificationBenchmark classificationBenchmark = new ClassificationBenchmark();

    public BenchmarkReport evaluate(List<EvaluationExample> examples,
            List<BenchmarkObservation> observations, BenchmarkGates gates) {
        requireCompleteUniqueCoverage(examples, observations);
        ClassificationMetrics metrics = classificationBenchmark.measure(examples,
                observations.stream().map(BenchmarkObservation::prediction).toList());
        int unsafeAccepted = (int) observations.stream()
                .filter(observation -> observation.accepted() && !observation.safetyPassed()).count();
        int citationFailures = (int) observations.stream()
                .filter(observation -> observation.accepted() && !observation.citationsPassed()).count();
        int adviceFailures = (int) observations.stream()
                .filter(observation -> observation.accepted() && !observation.adviceGuardPassed()).count();
        int usageMetadataFailures = (int) observations.stream()
                .filter(BenchmarkObservation::modelInvoked)
                .filter(observation -> observation.inputTokens() == 0 || observation.outputTokens() == 0
                        || observation.latencyMillis() == 0 || "UNKNOWN".equals(observation.stopReason()))
                .count();
        int inputTokens = observations.stream().mapToInt(BenchmarkObservation::inputTokens).sum();
        int outputTokens = observations.stream().mapToInt(BenchmarkObservation::outputTokens).sum();
        long p95Latency = percentile95(observations);

        List<String> violations = new ArrayList<>();
        if (metrics.precision() < gates.minimumPrecision()) violations.add("PRECISION_BELOW_GATE");
        if (metrics.recall() < gates.minimumRecall()) violations.add("RECALL_BELOW_GATE");
        if (metrics.f1() < gates.minimumF1()) violations.add("F1_BELOW_GATE");
        if (unsafeAccepted > 0) violations.add("UNSAFE_OUTPUT_ACCEPTED");
        if (citationFailures > 0) violations.add("CITATION_VALIDATION_FAILED");
        if (adviceFailures > 0) violations.add("ADVICE_GUARD_FAILED");
        if (usageMetadataFailures > 0) violations.add("USAGE_METADATA_MISSING");
        return new BenchmarkReport(metrics, inputTokens, outputTokens, p95Latency,
                unsafeAccepted, citationFailures, adviceFailures, usageMetadataFailures,
                violations.isEmpty(), violations);
    }

    private static void requireCompleteUniqueCoverage(List<EvaluationExample> examples,
            List<BenchmarkObservation> observations) {
        if (examples.isEmpty()) throw new IllegalArgumentException("benchmark examples are required");
        Set<String> expected = new HashSet<>();
        for (EvaluationExample example : examples) {
            if (!expected.add(example.id())) throw new IllegalArgumentException("duplicate example: " + example.id());
        }
        Set<String> actual = new HashSet<>();
        for (BenchmarkObservation observation : observations) {
            if (!actual.add(observation.exampleId())) {
                throw new IllegalArgumentException("duplicate observation: " + observation.exampleId());
            }
        }
        if (!expected.equals(actual)) throw new IllegalArgumentException("observations must cover exactly the fixture IDs");
    }

    private static long percentile95(List<BenchmarkObservation> observations) {
        List<Long> ordered = observations.stream().filter(BenchmarkObservation::modelInvoked)
                .map(BenchmarkObservation::latencyMillis)
                .sorted(Comparator.naturalOrder()).toList();
        if (ordered.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(ordered.size() * 0.95) - 1);
        return ordered.get(index);
    }
}
