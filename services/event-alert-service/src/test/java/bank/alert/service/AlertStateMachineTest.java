package bank.alert.service;

import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertStateMachineTest {

    private final AlertStateMachine stateMachine = new AlertStateMachine();

    @Test
    void mapsAgentDecisionsToStates() {
        assertThat(stateMachine.transition(AlertStatus.NEW, AlertDecisionType.INFORM_CLIENT))
                .isEqualTo(AlertStatus.INFORMED);
        assertThat(stateMachine.transition(AlertStatus.NEW, AlertDecisionType.MONITOR))
                .isEqualTo(AlertStatus.MONITORING);
        assertThat(stateMachine.transition(AlertStatus.NEW, AlertDecisionType.DISMISS))
                .isEqualTo(AlertStatus.DISMISSED);
    }

    @Test
    void allowsMonitoringAlertToBeResolved() {
        assertThat(stateMachine.transition(AlertStatus.MONITORING, AlertDecisionType.INFORM_CLIENT))
                .isEqualTo(AlertStatus.INFORMED);
        assertThat(stateMachine.transition(AlertStatus.MONITORING, AlertDecisionType.DISMISS))
                .isEqualTo(AlertStatus.DISMISSED);
    }

    @Test
    void rejectsChangesToTerminalState() {
        assertThatThrownBy(() -> stateMachine.transition(AlertStatus.DISMISSED, AlertDecisionType.MONITOR))
                .isInstanceOf(InvalidAlertTransitionException.class)
                .hasMessageContaining("terminal status");
    }
}
