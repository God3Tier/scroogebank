package bank.clienthealth.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import bank.clienthealth.explanation.ExplanationMetricsPort.Event;
import bank.clienthealth.explanation.ExplanationMetricsPort.Type;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MicrometerExplanationMetricsTest {
    @Test
    void recordsSuccessfulAttemptsTokensLatencyAndCostWithoutModelTag() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerExplanationMetrics(registry);
        metrics.record(new Event(Type.MODEL_CALL, "end_turn", "private-model-id", 2,
                11, 7, 25, new BigDecimal("0.0004")));

        assertThat(registry.get("client_health_ai_calls_total")
                .tags("outcome", "success", "source", "bedrock").counter().count()).isEqualTo(2);
        assertThat(registry.get("client_health_ai_input_tokens_total").counter().count()).isEqualTo(11);
        assertThat(registry.get("client_health_ai_output_tokens_total").counter().count()).isEqualTo(7);
        assertThat(registry.get("client_health_ai_estimated_cost_usd").counter().count()).isEqualTo(0.0004);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .noneSatisfy(tag -> assertThat(tag.getValue()).contains("private-model-id")));
    }

    @Test
    void disabledInvalidAndProviderFailureUseCanonicalFallbackTags() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerExplanationMetrics(registry);
        metrics.record(new Event(Type.FALLBACK, "AI_DISABLED", "ignored", 0, 0, 0, 0, null));
        metrics.record(new Event(Type.VALIDATION_REJECTION, "invented number", "ignored", 0, 0, 0, 0, null));
        metrics.record(new Event(Type.FALLBACK, "invented number", "ignored", 0, 0, 0, 0, null));
        metrics.record(new Event(Type.PROVIDER_FAILURE, "MODEL_TIMEOUT", "ignored", 1, 0, 0, 0, null));

        assertThat(registry.get("client_health_ai_fallback_total")
                .tag("reason", "ai_disabled").counter().count()).isEqualTo(1);
        assertThat(registry.get("client_health_ai_validation_rejected_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("client_health_ai_fallback_total")
                .tag("reason", "validation_rejection").counter().count()).isEqualTo(1);
        assertThat(registry.get("client_health_ai_provider_failures_total")
                .tag("outcome", "timeout").counter().count()).isEqualTo(1);
    }
}
