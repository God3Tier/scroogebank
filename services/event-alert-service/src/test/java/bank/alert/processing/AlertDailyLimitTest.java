package bank.alert.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The agent's daily alert limit (alert.generation.daily-limit): 200 by default, 0 for none. */
class AlertDailyLimitTest {

    @Test
    void stopsAtTheLimitCountingWhatTheAgentAlreadyHasToday() {
        assertThat(AlertGenerationService.limitReached(0, 199, 200)).isFalse();
        assertThat(AlertGenerationService.limitReached(150, 49, 200)).isFalse();
        assertThat(AlertGenerationService.limitReached(150, 50, 200)).isTrue();
        assertThat(AlertGenerationService.limitReached(200, 0, 200)).isTrue();
    }

    @Test
    void aLimitOfZeroOrLessMeansNoLimit() {
        assertThat(AlertGenerationService.limitReached(5_000, 5_000, 0)).isFalse();
        assertThat(AlertGenerationService.limitReached(5_000, 5_000, -1)).isFalse();
    }

    @Test
    void theDefaultIsTwoHundredAlertsAnAgentADay() {
        assertThat(AlertGenerationService.DEFAULT_DAILY_ALERT_LIMIT).isEqualTo(200);
    }
}
