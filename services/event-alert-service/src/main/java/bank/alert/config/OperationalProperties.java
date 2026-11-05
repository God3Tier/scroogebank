package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "alert.operations")
public record OperationalProperties(boolean ingestionEnabled, boolean modelEnabled,
        int monthlySearchQuota, int maxSearchesPerRun, long monthlyModelTokenQuota,
        int maxModelTokensPerInvocation, BigDecimal monthlyBudgetUsd, BigDecimal estimatedSearchCostUsd) {
    @ConstructorBinding
    public OperationalProperties {
        monthlySearchQuota = positiveOrDefault(monthlySearchQuota, 1_000, "monthlySearchQuota");
        maxSearchesPerRun = positiveOrDefault(maxSearchesPerRun, 1_000, "maxSearchesPerRun");
        if (monthlyModelTokenQuota < 0) {
            throw new IllegalArgumentException("monthlyModelTokenQuota cannot be negative");
        }
        monthlyModelTokenQuota = monthlyModelTokenQuota == 0 ? 200_000 : monthlyModelTokenQuota;
        maxModelTokensPerInvocation = positiveOrDefault(
                maxModelTokensPerInvocation, 2_000, "maxModelTokensPerInvocation");
        monthlyBudgetUsd = monthlyBudgetUsd == null ? new BigDecimal("25.00") : monthlyBudgetUsd;
        estimatedSearchCostUsd = estimatedSearchCostUsd == null ? new BigDecimal("0.008") : estimatedSearchCostUsd;
        if (estimatedSearchCostUsd.signum() < 0) {
            throw new IllegalArgumentException("alert.operations.estimated-search-cost-usd cannot be negative");
        }
        if (monthlyBudgetUsd.signum() <= 0) {
            throw new IllegalArgumentException("alert.operations.monthly-budget-usd must be positive");
        }
        if (maxSearchesPerRun > monthlySearchQuota) {
            throw new IllegalArgumentException("maxSearchesPerRun cannot exceed monthlySearchQuota");
        }
        if (maxModelTokensPerInvocation > monthlyModelTokenQuota) {
            throw new IllegalArgumentException("maxModelTokensPerInvocation cannot exceed monthlyModelTokenQuota");
        }
    }

    /** Without an estimated search cost, which then defaults to the Tavily pay-as-you-go price. */
    public OperationalProperties(boolean ingestionEnabled, boolean modelEnabled, int monthlySearchQuota,
            int maxSearchesPerRun, long monthlyModelTokenQuota, int maxModelTokensPerInvocation,
            BigDecimal monthlyBudgetUsd) {
        this(ingestionEnabled, modelEnabled, monthlySearchQuota, maxSearchesPerRun, monthlyModelTokenQuota,
                maxModelTokensPerInvocation, monthlyBudgetUsd, null);
    }

    private static int positiveOrDefault(int value, int defaultValue, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " cannot be negative");
        return value == 0 ? defaultValue : value;
    }
}
