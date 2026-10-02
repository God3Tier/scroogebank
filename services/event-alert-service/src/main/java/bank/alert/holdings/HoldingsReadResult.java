package bank.alert.holdings;

import bank.alert.domain.HoldingSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record HoldingsReadResult(
        int contractVersion,
        Instant asOf,
        List<HoldingSnapshot> positions,
        List<UnavailableHolding> unavailable) {

    public HoldingsReadResult {
        if (contractVersion != 1) {
            throw new IllegalArgumentException("unsupported holdings contract version");
        }
        Objects.requireNonNull(asOf);
        positions = List.copyOf(positions);
        unavailable = List.copyOf(unavailable);
    }
}
