package bank.alert.ai.evaluation;

import bank.alert.ai.EventType;

public record BenchmarkObservation(String exampleId, EventType predictedType, boolean accepted,
        boolean safetyPassed, boolean citationsPassed, boolean adviceGuardPassed,
        boolean modelInvoked, String stopReason, int inputTokens, int outputTokens, long latencyMillis) {
    public BenchmarkObservation {
        if (exampleId == null || exampleId.isBlank()) {
            throw new IllegalArgumentException("exampleId is required");
        }
        if (inputTokens < 0 || outputTokens < 0 || latencyMillis < 0) {
            throw new IllegalArgumentException("benchmark usage values cannot be negative");
        }
        if (stopReason == null || stopReason.isBlank()) {
            throw new IllegalArgumentException("stopReason is required");
        }
    }

    EvaluationPrediction prediction() {
        return new EvaluationPrediction(exampleId, predictedType, accepted);
    }
}
