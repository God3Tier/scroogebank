package bank.alert.scoring;

import bank.alert.domain.HoldingSnapshot;
import bank.alert.domain.ValuationFreshness;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExposureCalculatorTest {

    private final ExposureCalculator calculator = new ExposureCalculator();
    private final Instant valuedAt = Instant.parse("2026-09-27T00:00:00Z");

    @Test
    void calculatesCurrencyConvertedExposureWithFixedPrecision() {
        HoldingSnapshot affectedUsd = snapshot(
                "SYN-NYSE-001", "10", "20", "USD", "1.25", "250.0000",
                valuedAt, ValuationFreshness.FRESH);
        HoldingSnapshot otherSgd = snapshot(
                "SYN-SGX-002", "30", "25", "SGD", "1", "750.0000",
                valuedAt, ValuationFreshness.FRESH);

        ExposureResult result = calculator.calculate(
                List.of(affectedUsd, otherSgd), Set.of("SYN-NYSE-001"));

        assertThat(result.status()).isEqualTo(ExposureStatus.EVALUABLE);
        assertThat(result.amountSgd()).isEqualByComparingTo("250.0000");
        assertThat(result.totalPortfolioValueSgd()).isEqualByComparingTo("1000.0000");
        assertThat(result.portfolioPercentage()).isEqualByComparingTo("25.0000");
        assertThat(result.warning()).isNull();
    }

    @Test
    void preservesStaleWarningAndRejectsExpiredValues() {
        HoldingSnapshot stale = snapshot(
                "SYN-SGX-001", "10", "25", "SGD", "1", "250.0000",
                valuedAt, ValuationFreshness.STALE);
        ExposureResult staleResult = calculator.calculate(List.of(stale), Set.of("SYN-SGX-001"));
        assertThat(staleResult.evaluable()).isTrue();
        assertThat(staleResult.warning()).contains("older than 24 hours");

        HoldingSnapshot expired = snapshot(
                "SYN-SGX-001", "10", "25", "SGD", "1", "250.0000",
                valuedAt, ValuationFreshness.EXPIRED);
        assertThat(calculator.calculate(List.of(expired), Set.of("SYN-SGX-001")).status())
                .isEqualTo(ExposureStatus.EXPIRED_VALUATION);
    }

    @Test
    void reportsMissingAndInvalidTotalsWithoutFabricatingZeroExposure() {
        assertThat(calculator.calculate(List.of(), Set.of("SYN-SGX-001")).status())
                .isEqualTo(ExposureStatus.MISSING_PORTFOLIO_TOTAL);

        HoldingSnapshot invalid = snapshot(
                "SYN-SGX-001", "10", "25", "SGD", "1", "999.0000",
                valuedAt, ValuationFreshness.FRESH);
        ExposureResult invalidResult = calculator.calculate(List.of(invalid), Set.of("SYN-SGX-001"));
        assertThat(invalidResult.status()).isEqualTo(ExposureStatus.INVALID_VALUATION);
        assertThat(invalidResult.portfolioPercentage()).isNull();
    }

    @Test
    void refusesIncompatiblePortfolioValuationTimes() {
        HoldingSnapshot older = snapshot(
                "SYN-SGX-001", "10", "25", "SGD", "1", "250.0000",
                valuedAt.minusSeconds(25 * 60 * 60), ValuationFreshness.STALE);
        HoldingSnapshot newer = snapshot(
                "SYN-SGX-002", "10", "25", "SGD", "1", "250.0000",
                valuedAt, ValuationFreshness.FRESH);

        assertThat(calculator.calculate(List.of(older, newer), Set.of("SYN-SGX-001")).status())
                .isEqualTo(ExposureStatus.INCOMPATIBLE_VALUATION_TIMES);
    }

    @Test
    void refusesPositionsFromDifferentClientScopes() {
        HoldingSnapshot first = snapshot(
                "SYN-SGX-001", "10", "25", "SGD", "1", "250.0000",
                valuedAt, ValuationFreshness.FRESH);
        HoldingSnapshot otherClient = new HoldingSnapshot(
                UUID.randomUUID(), "syn-client-002", "syn-account-002", "syn-agent-001",
                "SYN-SGX-002", new BigDecimal("10"), new BigDecimal("25"), "SGD",
                BigDecimal.ONE, new BigDecimal("250.0000"), valuedAt,
                ValuationFreshness.FRESH, "synthetic-holdings-v1");

        assertThat(calculator.calculate(List.of(first, otherClient), Set.of("SYN-SGX-001")).status())
                .isEqualTo(ExposureStatus.MIXED_PORTFOLIO_SCOPE);
    }

    private static HoldingSnapshot snapshot(
            String instrumentId,
            String quantity,
            String unitPrice,
            String currency,
            String fxToSgd,
            String valueSgd,
            Instant valuedAt,
            ValuationFreshness freshness) {
        return new HoldingSnapshot(
                UUID.randomUUID(), "syn-client-001", "syn-account-001", "syn-agent-001",
                instrumentId, new BigDecimal(quantity), new BigDecimal(unitPrice), currency,
                new BigDecimal(fxToSgd), new BigDecimal(valueSgd), valuedAt, freshness,
                "synthetic-holdings-v1");
    }
}
