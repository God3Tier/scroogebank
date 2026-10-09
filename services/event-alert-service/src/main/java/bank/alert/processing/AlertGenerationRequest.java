package bank.alert.processing;

import bank.alert.ai.AgentBriefing;
import bank.alert.ai.EventClassification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AlertGenerationRequest(
        UUID sourceResultId,
        EventClassification classification,
        AgentBriefing briefing,
        String assignedAgentSubject,
        BigDecimal eventSeverity,
        BigDecimal retrievalRelevance,
        Instant asOf) {

    public AlertGenerationRequest {
        Objects.requireNonNull(sourceResultId);
        Objects.requireNonNull(classification);
        if (assignedAgentSubject == null || assignedAgentSubject.isBlank()) {
            throw new IllegalArgumentException("assignedAgentSubject is required");
        }
        requireUnitInterval(eventSeverity, "eventSeverity");
        requireUnitInterval(retrievalRelevance, "retrievalRelevance");
        Objects.requireNonNull(asOf);
    }

    private static void requireUnitInterval(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be between zero and one");
        }
    }
}
