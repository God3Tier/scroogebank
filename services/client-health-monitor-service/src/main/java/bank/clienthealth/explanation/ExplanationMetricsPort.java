package bank.clienthealth.explanation;

import java.math.BigDecimal;

@FunctionalInterface
public interface ExplanationMetricsPort {
    void record(Event event);

    static ExplanationMetricsPort noOp() { return event -> { }; }

    record Event(
            Type type, String reason, String modelId, int attempt,
            int inputTokens, int outputTokens, long latencyMillis,
            BigDecimal estimatedCostUsd
    ) { }

    enum Type { MODEL_CALL, VALIDATION_REJECTION, PROVIDER_FAILURE, FALLBACK }
}
