package bank.alert.portfolio;

import bank.alert.domain.ValuationFreshness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PortfolioResponse(
        UUID accountId,
        UUID clientId,
        long revision,
        String sourceLabel,
        Instant updatedAt,
        BigDecimal totalSgd,
        List<PositionResponse> positions) {

    public PortfolioResponse {
        positions = List.copyOf(positions);
    }

    public record PositionResponse(
            String instrumentId,
            String ticker,
            String issuerName,
            BigDecimal quantity,
            BigDecimal unitPrice,
            String valuationCurrency,
            BigDecimal fxToSgd,
            BigDecimal valueSgd,
            Instant valuedAt,
            ValuationFreshness freshness) {
    }
}
