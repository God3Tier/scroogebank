package bank.alert.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalPropertiesTest {
    @Test
    void suppliesGovernedDefaultsWithoutEnablingOutboundCalls() {
        OperationalProperties properties = new OperationalProperties(
                false, false, 0, 0, 0, 0, null);

        assertThat(properties.ingestionEnabled()).isFalse();
        assertThat(properties.modelEnabled()).isFalse();
        assertThat(properties.monthlySearchQuota()).isEqualTo(1_000);
        assertThat(properties.maxSearchesPerRun()).isEqualTo(200);
        assertThat(properties.monthlyModelTokenQuota()).isEqualTo(200_000);
        assertThat(properties.maxModelTokensPerInvocation()).isEqualTo(2_000);
        assertThat(properties.monthlyBudgetUsd()).isEqualByComparingTo("25.00");
    }

    @Test
    void rejectsInternallyInconsistentLimits() {
        assertThatThrownBy(() -> new OperationalProperties(
                true, true, 3, 4, 100, 50, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSearchesPerRun");
        assertThatThrownBy(() -> new OperationalProperties(
                true, true, 5, 4, 40, 50, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxModelTokensPerInvocation");
        assertThatThrownBy(() -> new OperationalProperties(
                true, true, 5, 4, 100, 50, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("monthly-budget-usd");
    }
}
