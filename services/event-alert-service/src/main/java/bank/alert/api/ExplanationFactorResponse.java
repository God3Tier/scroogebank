package bank.alert.api;

import java.math.BigDecimal;

public record ExplanationFactorResponse(
        String code,
        BigDecimal contribution,
        String description) {
}
