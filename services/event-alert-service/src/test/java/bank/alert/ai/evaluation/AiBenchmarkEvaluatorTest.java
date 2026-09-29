package bank.alert.ai.evaluation;

import bank.alert.ai.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiBenchmarkEvaluatorTest {
    private final AiBenchmarkEvaluator evaluator = new AiBenchmarkEvaluator();

    @Test
    void passesACompleteSafeRunAtTheCandidateQualityGates() {
        List<EvaluationExample> examples = examples();
        BenchmarkReport report = evaluator.evaluate(examples, List.of(
                observation("one", EventType.EARNINGS, true, true, true, true, 100),
                observation("two", EventType.REGULATORY, true, true, true, true, 200),
                observation("three", null, false, true, true, true, 300),
                observation("four", EventType.CREDIT, true, true, true, true, 400)),
                BenchmarkGates.candidateDefaults());

        assertThat(report.passed()).isTrue();
        assertThat(report.violations()).isEmpty();
        assertThat(report.classification().precision()).isEqualTo(1.0);
        assertThat(report.classification().recall()).isEqualTo(1.0);
        assertThat(report.totalInputTokens()).isEqualTo(40);
        assertThat(report.totalOutputTokens()).isEqualTo(20);
        assertThat(report.p95LatencyMillis()).isEqualTo(400);
    }

    @Test
    void reportsEveryFailedQualityAndSafetyGate() {
        BenchmarkReport report = evaluator.evaluate(examples(), List.of(
                observation("one", EventType.OTHER, true, false, false, false, 100),
                observation("two", null, false, true, true, true, 200),
                observation("three", EventType.OTHER, true, true, true, true, 300),
                observation("four", null, false, true, true, true, 400)),
                BenchmarkGates.candidateDefaults());

        assertThat(report.passed()).isFalse();
        assertThat(report.violations()).containsExactly(
                "PRECISION_BELOW_GATE", "RECALL_BELOW_GATE", "F1_BELOW_GATE",
                "UNSAFE_OUTPUT_ACCEPTED", "CITATION_VALIDATION_FAILED", "ADVICE_GUARD_FAILED");
        assertThat(report.unsafeAccepted()).isOne();
        assertThat(report.citationFailures()).isOne();
        assertThat(report.adviceFailures()).isOne();
        assertThat(report.usageMetadataFailures()).isZero();
    }

    @Test
    void rejectsPartialOrDuplicateRunsInsteadOfReportingMisleadingMetrics() {
        assertThatThrownBy(() -> evaluator.evaluate(examples(), List.of(
                observation("one", EventType.EARNINGS, true, true, true, true, 100)),
                BenchmarkGates.candidateDefaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cover exactly");

        assertThatThrownBy(() -> evaluator.evaluate(examples(), List.of(
                observation("one", EventType.EARNINGS, true, true, true, true, 100),
                observation("one", EventType.EARNINGS, true, true, true, true, 100),
                observation("three", null, false, true, true, true, 300),
                observation("four", EventType.CREDIT, true, true, true, true, 400)),
                BenchmarkGates.candidateDefaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate observation");
    }

    @Test
    void failsAnInvokedRunWhenProviderUsageMetadataIsMissing() {
        List<BenchmarkObservation> observations = List.of(
                new BenchmarkObservation("one", EventType.EARNINGS, true, true, true, true,
                        true, "end_turn", 0, 5, 100),
                observation("two", EventType.REGULATORY, true, true, true, true, 200),
                new BenchmarkObservation("three", null, false, true, true, true,
                        false, "NOT_INVOKED", 0, 0, 0),
                observation("four", EventType.CREDIT, true, true, true, true, 400));

        BenchmarkReport report = evaluator.evaluate(examples(), observations,
                BenchmarkGates.candidateDefaults());

        assertThat(report.passed()).isFalse();
        assertThat(report.usageMetadataFailures()).isOne();
        assertThat(report.violations()).containsExactly("USAGE_METADATA_MISSING");
    }

    private static List<EvaluationExample> examples() {
        return List.of(
                new EvaluationExample("one", EventType.EARNINGS, true),
                new EvaluationExample("two", EventType.REGULATORY, true),
                new EvaluationExample("three", null, false),
                new EvaluationExample("four", EventType.CREDIT, true));
    }

    private static BenchmarkObservation observation(String id, EventType type, boolean accepted,
            boolean safe, boolean cited, boolean adviceGuard, long latency) {
        return new BenchmarkObservation(id, type, accepted, safe, cited, adviceGuard,
                true, "end_turn", 10, 5, latency);
    }
}
