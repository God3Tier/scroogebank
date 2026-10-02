package bank.alert.ai;

import java.util.Objects;

public record AiModelResponse(String outputJson, int inputTokens, int outputTokens,
        long latencyMillis, String stopReason) {
    public AiModelResponse {
        outputJson = Objects.requireNonNull(outputJson);
        if (inputTokens < 0 || outputTokens < 0 || latencyMillis < 0) {
            throw new IllegalArgumentException("model usage values cannot be negative");
        }
        stopReason = stopReason == null || stopReason.isBlank() ? "UNKNOWN" : stopReason;
    }
}
