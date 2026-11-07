package bank.engagement.integration.sources;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param healthBaseUrl client-health-monitor-service; blank fails every task lookup closed
 * @param alertsBaseUrl event-alert-service; blank fails every alert lookup closed
 * @param timeout       connect and read timeout for one call
 * @param pdfTimeout    read timeout for the combined report PDF, which is rendered on the send (news-alert-drafts
 *                      plan, section 11)
 */
@ConfigurationProperties("engagement.sources")
public record FollowUpSourceProperties(String healthBaseUrl, String alertsBaseUrl, Duration timeout, Duration pdfTimeout) {

    public FollowUpSourceProperties {
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        pdfTimeout = pdfTimeout == null ? Duration.ofSeconds(15) : pdfTimeout;
    }
}
