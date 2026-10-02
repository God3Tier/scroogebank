package bank.alert.service;

import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class AlertStateMachine {

    private static final Map<AlertDecisionType, AlertStatus> TARGETS = new EnumMap<>(AlertDecisionType.class);

    static {
        TARGETS.put(AlertDecisionType.INFORM_CLIENT, AlertStatus.INFORMED);
        TARGETS.put(AlertDecisionType.MONITOR, AlertStatus.MONITORING);
        TARGETS.put(AlertDecisionType.DISMISS, AlertStatus.DISMISSED);
    }

    public AlertStatus transition(AlertStatus current, AlertDecisionType decision) {
        if (current == null || decision == null) {
            throw new InvalidAlertTransitionException("current status and decision are required");
        }
        if (current != AlertStatus.NEW && current != AlertStatus.MONITORING) {
            throw new InvalidAlertTransitionException(
                    "alert in terminal status " + current + " cannot accept " + decision);
        }
        return TARGETS.get(decision);
    }
}
