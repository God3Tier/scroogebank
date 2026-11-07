package bank.alert.service;

import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The client statuses on a report. New, Plan client briefing (INFORMED) and Dismiss can move to Plan client briefing
 * or Dismiss, so an agent can change their mind until the email is sent. Sent Out is final and is set only by
 * engagement-service's sent-out call ({@link #canSendOut}), never by a decision.
 */
@Component
public class AlertStateMachine {

    private static final Map<AlertDecisionType, AlertStatus> TARGETS = new EnumMap<>(AlertDecisionType.class);
    private static final Set<AlertStatus> DECIDABLE = Set.of(AlertStatus.NEW, AlertStatus.INFORMED, AlertStatus.DISMISSED);

    static {
        TARGETS.put(AlertDecisionType.INFORM_CLIENT, AlertStatus.INFORMED);
        TARGETS.put(AlertDecisionType.DISMISS, AlertStatus.DISMISSED);
    }

    /** The status after the decision. Recording the status a client already has returns it unchanged. */
    public AlertStatus transition(AlertStatus current, AlertDecisionType decision) {
        if (current == null || decision == null) {
            throw new InvalidAlertTransitionException("current status and decision are required");
        }
        if (!decision.accepted()) {
            throw new InvalidAlertTransitionException(decision + " is no longer accepted");
        }
        if (!DECIDABLE.contains(current)) {
            throw new InvalidAlertTransitionException("alert in status " + current + " cannot accept " + decision);
        }
        return TARGETS.get(decision);
    }

    /** Whether the email that was just sent can move this client to Sent Out. */
    public boolean canSendOut(AlertStatus current) {
        return current == AlertStatus.INFORMED || current == AlertStatus.DISMISSED;
    }
}
