package bank.alert.scoring;

import bank.alert.domain.ValuationFreshness;

import java.math.BigDecimal;
import java.time.Instant;

public record ExposureResult(
        ExposureStatus status,
        BigDecimal amountSgd,
        BigDecimal totalPortfolioValueSgd,
        BigDecimal portfolioPercentage,
        Instant valuationAsOf,
        ValuationFreshness freshness,
        String warning) {

    public boolean evaluable() {
        return status == ExposureStatus.EVALUABLE;
    }

    public static ExposureResult unavailable(ExposureStatus status, String warning) {
        if (status == ExposureStatus.EVALUABLE) {
            throw new IllegalArgumentException("use an evaluable exposure result");
        }
        return new ExposureResult(status, null, null, null, null, null, warning);
    }
}
