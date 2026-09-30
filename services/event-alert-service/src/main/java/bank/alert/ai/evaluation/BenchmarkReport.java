package bank.alert.ai.evaluation;

import java.util.List;

public record BenchmarkReport(ClassificationMetrics classification, int totalInputTokens,
        int totalOutputTokens, long p95LatencyMillis, int unsafeAccepted,
        int citationFailures, int adviceFailures, int usageMetadataFailures,
        boolean passed, List<String> violations) {
    public BenchmarkReport {
        violations = List.copyOf(violations);
    }
}
