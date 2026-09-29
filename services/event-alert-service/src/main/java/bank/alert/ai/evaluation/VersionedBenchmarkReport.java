package bank.alert.ai.evaluation;

import java.math.BigDecimal;

public record VersionedBenchmarkReport(String fixtureVersion, String model,
        String promptVersion, String fixtureLabelStatus, BigDecimal estimatedCostUsd,
        boolean eligibleForEnablement, BenchmarkReport report) { }
