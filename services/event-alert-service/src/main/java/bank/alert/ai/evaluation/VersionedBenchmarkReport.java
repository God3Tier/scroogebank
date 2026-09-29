package bank.alert.ai.evaluation;

import java.math.BigDecimal;

public record VersionedBenchmarkReport(String fixtureVersion, String model,
        String promptVersion, BigDecimal estimatedCostUsd, BenchmarkReport report) { }
