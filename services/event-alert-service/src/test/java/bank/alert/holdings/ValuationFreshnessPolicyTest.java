package bank.alert.holdings;

import bank.alert.domain.ValuationFreshness;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValuationFreshnessPolicyTest {

    private final ValuationFreshnessPolicy policy = new ValuationFreshnessPolicy();
    private final Instant asOf = Instant.parse("2026-09-27T00:00:00Z");

    @Test
    void classifiesFreshStaleAndExpiredBoundaries() {
        assertThat(policy.classify(Instant.parse("2026-09-26T00:00:00Z"), asOf))
                .isEqualTo(ValuationFreshness.FRESH);
        assertThat(policy.classify(Instant.parse("2026-09-25T23:59:59Z"), asOf))
                .isEqualTo(ValuationFreshness.STALE);
        assertThat(policy.classify(Instant.parse("2026-09-24T00:00:00Z"), asOf))
                .isEqualTo(ValuationFreshness.STALE);
        assertThat(policy.classify(Instant.parse("2026-09-23T23:59:59Z"), asOf))
                .isEqualTo(ValuationFreshness.EXPIRED);
    }

    @Test
    void rejectsFutureValuation() {
        assertThatThrownBy(() -> policy.classify(
                Instant.parse("2026-09-27T00:00:01Z"), asOf))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
