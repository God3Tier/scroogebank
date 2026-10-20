package bank.alert.config;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayPropertiesTest {

    private static ReplayProperties of(String from, String to, String operator, String correlationId) {
        return new ReplayProperties(from, to, operator, correlationId);
    }

    @Test
    void noReplayWhenNothingIsSet() {
        assertThat(of(null, " ", "", null).requested()).isFalse();
    }

    @Test
    void anyValueMakesItARequestSoAHalfFilledOneFailsClosed() {
        ReplayProperties onlyOperator = of(null, null, "operator-1", null);

        assertThat(onlyOperator.requested()).isTrue();
        assertThatThrownBy(onlyOperator::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCompleteRequestIsValid() {
        ReplayProperties replay = of("2026-10-14T01:00:00Z", "2026-10-14T03:00:00Z", "operator-1", "corr-1");

        assertThatCode(replay::validate).doesNotThrowAnyException();
        assertThat(replay.windowFrom()).isEqualTo(Instant.parse("2026-10-14T01:00:00Z"));
    }

    @Test
    void requiresAnOperatorAndACorrelationId() {
        assertThatThrownBy(() -> of("2026-10-14T01:00:00Z", "2026-10-14T03:00:00Z", null, "corr-1").validate())
                .hasMessageContaining("operator");
        assertThatThrownBy(() -> of("2026-10-14T01:00:00Z", "2026-10-14T03:00:00Z", "operator-1", null).validate())
                .hasMessageContaining("correlation");
    }

    @Test
    void rejectsLongNamesAndBadOrReversedWindows() {
        String tooLong = "x".repeat(129);
        assertThatThrownBy(() -> of("2026-10-14T01:00:00Z", "2026-10-14T03:00:00Z", tooLong, "c").validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of("yesterday", "2026-10-14T03:00:00Z", "o", "c").validate())
                .hasMessageContaining("ISO-8601");
        assertThatThrownBy(() -> of("2026-10-14T03:00:00Z", "2026-10-14T01:00:00Z", "o", "c").validate())
                .hasMessageContaining("precede");
    }
}
