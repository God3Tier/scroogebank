package bank.alert.ai.evaluation;

import bank.alert.ai.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificationBenchmarkTest {
    @Test
    void reportsPrecisionRecallF1AndAbstentionWithoutHidingErrors() {
        ClassificationMetrics metrics = new ClassificationBenchmark().measure(List.of(
                new EvaluationExample("one", EventType.EARNINGS, true),
                new EvaluationExample("two", EventType.REGULATORY, true),
                new EvaluationExample("three", null, false),
                new EvaluationExample("four", EventType.CREDIT, true)), List.of(
                new EvaluationPrediction("one", EventType.EARNINGS, true),
                new EvaluationPrediction("two", EventType.OTHER, true),
                new EvaluationPrediction("three", EventType.OTHER, true),
                new EvaluationPrediction("four", null, false)));

        assertThat(metrics.truePositives()).isOne();
        assertThat(metrics.falsePositives()).isEqualTo(2);
        assertThat(metrics.falseNegatives()).isEqualTo(2);
        assertThat(metrics.abstentions()).isOne();
        assertThat(metrics.precision()).isCloseTo(1d / 3d, within(0.0001));
        assertThat(metrics.recall()).isCloseTo(1d / 3d, within(0.0001));
        assertThat(metrics.f1()).isCloseTo(1d / 3d, within(0.0001));
        assertThat(metrics.abstentionRate()).isEqualTo(0.25);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
