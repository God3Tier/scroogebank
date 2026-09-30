package bank.alert.processing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SourceProcessingRequest(
        UUID sourceResultId,
        String excerpt,
        List<String> candidateInstrumentIds,
        String assignedAgentSubject,
        BigDecimal eventSeverity,
        BigDecimal retrievalRelevance,
        Instant asOf) {

    public SourceProcessingRequest {
        Objects.requireNonNull(sourceResultId);
        if (excerpt == null || excerpt.isBlank()) throw new IllegalArgumentException("excerpt is required");
        candidateInstrumentIds = List.copyOf(candidateInstrumentIds);
        if (candidateInstrumentIds.isEmpty()) {
            throw new IllegalArgumentException("candidateInstrumentIds cannot be empty");
        }
        if (assignedAgentSubject == null || assignedAgentSubject.isBlank()) {
            throw new IllegalArgumentException("assignedAgentSubject is required");
        }
        Objects.requireNonNull(eventSeverity);
        Objects.requireNonNull(retrievalRelevance);
        Objects.requireNonNull(asOf);
    }
}
