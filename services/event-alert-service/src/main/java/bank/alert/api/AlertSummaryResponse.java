package bank.alert.api;

import bank.alert.domain.AlertStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AlertSummaryResponse(
        UUID id,
        String clientRef,
        String instrumentId,
        String headline,
        AlertStatus status,
        BigDecimal score,
        Instant createdAt,
        long version) {
}
