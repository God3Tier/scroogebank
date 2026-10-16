package bank.alert.holdings;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record HoldingsReadRequest(
        Set<String> instrumentIds,
        Instant asOf,
        String assignedAgentSubject) {

    public HoldingsReadRequest {
        Objects.requireNonNull(instrumentIds);
        Objects.requireNonNull(asOf);
        if (assignedAgentSubject == null || assignedAgentSubject.isBlank()) {
            throw new IllegalArgumentException("assignedAgentSubject must not be blank");
        }
        if (instrumentIds.isEmpty() || instrumentIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("instrumentIds must contain at least one non-blank ID");
        }
        instrumentIds = Set.copyOf(instrumentIds);
    }
}
