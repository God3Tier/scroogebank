package bank.engagement.alertreports;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param enabled          the rollout kill switch (ADR-0084); off, nothing is queued or sent
 * @param processingBudget how long one request keeps sending; the rest stays QUEUED until the same request is sent again
 * @param maxAttempts      failed attempts for a recipient before it is FAILED
 */
@ConfigurationProperties("engagement.alert-reports")
public record AlertReportProperties(boolean enabled, Duration processingBudget, int maxAttempts) {

    public AlertReportProperties {
        processingBudget = processingBudget == null ? Duration.ofSeconds(20) : processingBudget;
        maxAttempts = maxAttempts < 1 ? 3 : maxAttempts;
    }
}
