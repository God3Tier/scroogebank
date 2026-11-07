package bank.alert.ai.evaluation;

public record BenchmarkGates(double minimumPrecision, double minimumRecall, double minimumF1) {
    public static BenchmarkGates candidateDefaults() {
        return new BenchmarkGates(0.80, 0.75, 0.77);
    }

    public BenchmarkGates {
        validateRate(minimumPrecision, "minimumPrecision");
        validateRate(minimumRecall, "minimumRecall");
        validateRate(minimumF1, "minimumF1");
    }

    private static void validateRate(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be between zero and one");
        }
    }
}
