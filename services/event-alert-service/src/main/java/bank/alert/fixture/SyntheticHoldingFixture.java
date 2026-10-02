package bank.alert.fixture;

import bank.alert.domain.ValuationFreshness;
import bank.alert.holdings.UnavailableHolding;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SyntheticHoldingFixture(
        int contractVersion,
        Instant asOf,
        List<Position> positions,
        List<UnavailableHolding> unavailable) {

    public record Position(
            UUID snapshotId,
            String clientRef,
            String accountRef,
            String assignedAgentSubject,
            String instrumentId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            String valuationCurrency,
            BigDecimal fxToSgd,
            BigDecimal valueSgd,
            Instant valuedAt,
            ValuationFreshness freshness,
            String sourceVersion) {
    }
}
