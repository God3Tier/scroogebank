package bank.alert.scoring;

import bank.alert.domain.ValuationFreshness;

import java.math.BigDecimal;
import java.util.Objects;

public record MaterialityInputs(
        BigDecimal eventSeverity,
        BigDecimal exposureRatio,
        BigDecimal retrievalRelevance,
        BigDecimal classifierConfidence,
        ValuationFreshness valuationFreshness) {

    public MaterialityInputs {
        requireUnitInterval(eventSeverity, "eventSeverity");
        requireUnitInterval(exposureRatio, "exposureRatio");
        requireUnitInterval(retrievalRelevance, "retrievalRelevance");
        requireUnitInterval(classifierConfidence, "classifierConfidence");
        Objects.requireNonNull(valuationFreshness);
    }

    private static void requireUnitInterval(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }
}
