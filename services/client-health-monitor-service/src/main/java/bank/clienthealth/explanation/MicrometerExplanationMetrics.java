package bank.clienthealth.explanation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

/** Converts the SF07 metrics events into bounded, identifier-free Micrometer meters. */
public final class MicrometerExplanationMetrics implements ExplanationMetricsPort {
    private final MeterRegistry registry;

    public MicrometerExplanationMetrics(MeterRegistry registry) { this.registry = registry; }

    @Override
    public void record(Event event) {
        switch (event.type()) {
            case MODEL_CALL -> modelCall(event);
            case VALIDATION_REJECTION -> counter("client_health_ai_validation_rejected_total",
                    "outcome", "invalid_output").increment();
            case PROVIDER_FAILURE -> providerFailure(event);
            case FALLBACK -> counter("client_health_ai_fallback_total", "source", "template",
                    "reason", fallbackReason(event.reason())).increment();
        }
    }

    private void modelCall(Event event) {
        counter("client_health_ai_calls_total", "outcome", "success", "source", "bedrock")
                .increment(Math.max(1, event.attempt()));
        Timer.builder("client_health_ai_latency_seconds").tag("outcome", "success")
                .register(registry).record(Duration.ofMillis(Math.max(0, event.latencyMillis())));
        counter("client_health_ai_input_tokens_total", "source", "bedrock").increment(event.inputTokens());
        counter("client_health_ai_output_tokens_total", "source", "bedrock").increment(event.outputTokens());
        if (event.estimatedCostUsd() != null) {
            counter("client_health_ai_estimated_cost_usd", "source", "bedrock")
                    .increment(event.estimatedCostUsd().doubleValue());
        }
    }

    private void providerFailure(Event event) {
        String outcome = providerOutcome(event.reason());
        counter("client_health_ai_calls_total", "outcome", outcome, "source", "bedrock")
                .increment(Math.max(1, event.attempt()));
        counter("client_health_ai_provider_failures_total", "outcome", outcome).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private static String providerOutcome(String value) {
        String code = normalized(value);
        if (code.contains("TIMEOUT")) return "timeout";
        if (code.contains("THROTTL")) return "throttled";
        if (code.contains("ACCESS_DENIED")) return "access_denied";
        if (code.contains("INVALID") || code.contains("REJECTED") || code.contains("TOO_LONG")) {
            return "invalid_output";
        }
        return "unavailable";
    }

    private static String fallbackReason(String value) {
        String code = normalized(value);
        if (code.equals("AI_DISABLED")) return "ai_disabled";
        if (code.contains("TIMEOUT")) return "timeout";
        return code.startsWith("MODEL_") || code.startsWith("INPUT_")
                ? "provider_failure" : "validation_rejection";
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
