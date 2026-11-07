package bank.clienthealth.runtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("client-health.runtime")
public record ClientHealthRuntimeProperties(Mode mode, Duration leaseDuration) {
    public ClientHealthRuntimeProperties {
        if (mode == null) throw new IllegalArgumentException("client-health.runtime.mode is required");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("client-health.runtime.lease-duration must be positive");
        }
    }

    public enum Mode { API, ANALYSIS }
}
