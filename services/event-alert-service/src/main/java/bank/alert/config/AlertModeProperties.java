package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alert")
public record AlertModeProperties(AlertMode mode) {

    public AlertModeProperties {
        if (mode == null) {
            throw new IllegalArgumentException("alert.mode must be API or WORKER");
        }
    }
}
