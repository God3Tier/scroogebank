package bank.alert.ai.evaluation;

import java.math.BigDecimal;
import java.util.List;

public record BenchmarkRunFile(String model, String promptVersion,
        BigDecimal estimatedCostUsd, List<BenchmarkObservation> observations) {
    public BenchmarkRunFile {
        if (model == null || model.isBlank() || promptVersion == null || promptVersion.isBlank()
                || estimatedCostUsd == null || estimatedCostUsd.signum() < 0 || observations == null) {
            throw new IllegalArgumentException(
                    "model, promptVersion, non-negative estimatedCostUsd, and observations are required");
        }
        observations = List.copyOf(observations);
    }
}
