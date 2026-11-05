package bank.alert.api;

import bank.alert.domain.AlertStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AlertDetailResponse(
        UUID id,
        /** The event this alert belongs to; the event carries the combined report and its sources. */
        UUID eventId,
        String clientRef,
        String instrumentId,
        String headline,
        AlertStatus status,
        BigDecimal score,
        Instant createdAt,
        long version,
        ExposureResponse exposure,
        List<ExplanationFactorResponse> explanationFactors,
        List<EvidenceResponse> evidence,
        BriefingResponse briefing,
        DecisionResponse latestDecision) {

    public AlertDetailResponse {
        explanationFactors = List.copyOf(explanationFactors);
        evidence = List.copyOf(evidence);
    }
}
