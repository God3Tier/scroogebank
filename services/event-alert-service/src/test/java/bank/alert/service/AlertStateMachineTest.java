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
        assertThat(stateMachine.transition(AlertStatus.NEW, AlertDecisionType.DISMISS))
                .isEqualTo(AlertStatus.DISMISSED);
    }

    @Test
    void anAgentCanChangeTheirMindUntilTheEmailIsSent() {
        assertThat(stateMachine.transition(AlertStatus.INFORMED, AlertDecisionType.DISMISS))
                .isEqualTo(AlertStatus.DISMISSED);
        assertThat(stateMachine.transition(AlertStatus.DISMISSED, AlertDecisionType.INFORM_CLIENT))
                .isEqualTo(AlertStatus.INFORMED);
        assertThat(stateMachine.transition(AlertStatus.INFORMED, AlertDecisionType.INFORM_CLIENT))
                .as("recording the status a client already has changes nothing").isEqualTo(AlertStatus.INFORMED);
    }

    @Test
    void sentOutIsFinalAndMonitorIsNoLongerAccepted() {
        assertThatThrownBy(() -> stateMachine.transition(AlertStatus.SENT_OUT, AlertDecisionType.DISMISS))
                .isInstanceOf(InvalidAlertTransitionException.class);
        assertThatThrownBy(() -> stateMachine.transition(AlertStatus.NEW, AlertDecisionType.MONITOR))
                .isInstanceOf(InvalidAlertTransitionException.class)
                .hasMessageContaining("no longer accepted");
    }

    @Test
    void onlyAClientInPlanClientBriefingOrDismissCanBeSentOut() {
        assertThat(stateMachine.canSendOut(AlertStatus.INFORMED)).isTrue();
        assertThat(stateMachine.canSendOut(AlertStatus.DISMISSED)).isTrue();
        assertThat(stateMachine.canSendOut(AlertStatus.NEW)).isFalse();
        assertThat(stateMachine.canSendOut(AlertStatus.SENT_OUT)).isFalse();
    }
}
