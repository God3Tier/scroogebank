package bank.alert.api;

import bank.alert.domain.AlertDecision;
import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertStatus;

import java.time.Instant;
import java.util.UUID;

public record DecisionResponse(
        UUID id,
        UUID alertId,
        AlertDecisionType decision,
        AlertStatus previousStatus,
        AlertStatus newStatus,
        String actorSubject,
        Instant decidedAt,
        long alertVersion) {

    public static DecisionResponse from(AlertDecision decision) {
        return new DecisionResponse(
                decision.getId(), decision.getAlertId(), decision.getDecision(),
                decision.getPreviousStatus(), decision.getNewStatus(), decision.getActorSubject(),
                decision.getDecidedAt(), decision.getAlertVersion());
    }
}
