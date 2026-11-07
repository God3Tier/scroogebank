package bank.alert.holdings;

import java.util.Objects;

public record UnavailableHolding(
        String clientRef,
        String instrumentId,
        HoldingUnavailableReason reason) {

    public UnavailableHolding {
        if (clientRef != null && clientRef.isBlank()) {
            throw new IllegalArgumentException("clientRef must be null or non-blank");
        }
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId must not be blank");
        }
        Objects.requireNonNull(reason);
    }
}
