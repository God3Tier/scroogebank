package bank.alert.scoring;

import bank.alert.ai.EventClassification;
import bank.alert.ai.EventDirection;
import bank.alert.ai.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventSeverityPolicyTest {

    private final EventSeverityPolicy policy = new EventSeverityPolicy();

    private static EventClassification of(EventType type, EventDirection direction) {
        return new EventClassification("headline", type, direction, new BigDecimal("0.90"),
                List.of(), List.of(), "model", "prompt", Instant.parse("2026-10-20T00:00:00Z"));
    }

    @Test
    void regulatoryAndCreditEventsAreTheMostSevereByType() {
        assertThat(policy.severity(of(EventType.REGULATORY, EventDirection.UNCERTAIN))).isEqualByComparingTo("0.80");
        assertThat(policy.severity(of(EventType.CREDIT, EventDirection.POSITIVE))).isEqualByComparingTo("0.80");
        assertThat(policy.severity(of(EventType.EARNINGS, EventDirection.POSITIVE))).isEqualByComparingTo("0.60");
        assertThat(policy.severity(of(EventType.OTHER, EventDirection.MIXED))).isEqualByComparingTo("0.30");
    }

    @Test
    void aNegativeEventIsOneStepMoreSevereAndNeverAboveOne() {
        assertThat(policy.severity(of(EventType.EARNINGS, EventDirection.NEGATIVE))).isEqualByComparingTo("0.70");
        assertThat(policy.severity(of(EventType.REGULATORY, EventDirection.NEGATIVE))).isEqualByComparingTo("0.90");
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void everyEventTypeHasASeverityBetweenZeroAndOne(EventType type) {
        for (EventDirection direction : EventDirection.values()) {
            assertThat(policy.severity(of(type, direction)))
                    .isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        }
    }
}
