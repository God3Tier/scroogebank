package bank.engagement.integration.sources;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param healthBaseUrl client-health-monitor-service; blank fails every task lookup closed
 * @param alertsBaseUrl event-alert-service; blank fails every alert lookup closed
 * @param timeout       connect and read timeout for one call
 */
@ConfigurationProperties("engagement.sources")
public record FollowUpSourceProperties(String healthBaseUrl, String alertsBaseUrl, Duration timeout) {

    public FollowUpSourceProperties {
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }
}
