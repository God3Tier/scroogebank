package bank.clienthealth.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ClientHealthRuntimePropertiesTest {
    @Test
    void rejectsMissingMode() {
        assertThatThrownBy(() -> new ClientHealthRuntimeProperties(null, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }

    @Test
    void rejectsNonPositiveLeaseDuration() {
        assertThatThrownBy(() -> new ClientHealthRuntimeProperties(
                ClientHealthRuntimeProperties.Mode.API, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration");
    }
}
