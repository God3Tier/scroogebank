package bank.alert.operations;

import java.math.BigDecimal;
import java.time.YearMonth;

public record OperationalUsageSnapshot(YearMonth month, int searchQueries,
        long modelTokens, BigDecimal estimatedCostUsd) { }
