package bank.alert.api;

import bank.alert.domain.ValuationFreshness;

import java.math.BigDecimal;
import java.time.Instant;

public record ExposureResponse(
        String amountSgd,
        BigDecimal portfolioPercentage,
        Instant valuationAsOf,
        ValuationFreshness freshness) {
}
