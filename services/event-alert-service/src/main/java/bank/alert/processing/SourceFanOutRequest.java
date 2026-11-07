package bank.alert.processing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One stored source result to turn into alerts for every agent whose clients hold
 * an affected instrument (ADR-0049). The caller knows only which instruments the
 * text named and how directly; the classifier decides which are affected, and
 * severity, agents and exposure are worked out from that.
 */
public record SourceFanOutRequest(
        UUID sourceResultId,
        String excerpt,
        Map<String, BigDecimal> candidateRelevance,
        Instant asOf) {

    public SourceFanOutRequest {
        Objects.requireNonNull(sourceResultId);
        if (excerpt == null || excerpt.isBlank()) throw new IllegalArgumentException("excerpt is required");
        candidateRelevance = Map.copyOf(candidateRelevance);
        if (candidateRelevance.isEmpty()) {
            throw new IllegalArgumentException("candidateRelevance cannot be empty");
        }
        Objects.requireNonNull(asOf);
    }
}
