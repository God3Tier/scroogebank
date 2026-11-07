package bank.engagement.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * @param provider         {@code none} sends nothing and runs no dispatcher, {@code log}
 *                         logs message IDs only (local use), {@code ses} sends through SES
 * @param fromAddress      the {@code no-reply} sending address (ADR-0053 decision 3)
 * @param mailDomain       the sending domain, also the calendar {@code UID} suffix (ADR-0052)
 * @param crmAppUrl        the CRM's address, linked from invites and reminders
 * @param minSendInterval  the gap between two sends; SES sandbox allows 1 a second
 */
@ConfigurationProperties("engagement.mail")
public record MailProperties(
        String provider,
        String fromAddress,
        String mailDomain,
        String configurationSet,
        String crmAppUrl,
        String region,
        String endpoint,
        Duration minSendInterval,
        Duration sendTimeout,
        Dispatcher dispatcher) {

    public MailProperties {
        provider = provider == null || provider.isBlank() ? "none" : provider.strip().toLowerCase(Locale.ROOT);
        region = region == null || region.isBlank() ? "ap-southeast-1" : region;
        minSendInterval = minSendInterval == null ? Duration.ofSeconds(1) : minSendInterval;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(10) : sendTimeout;
        dispatcher = dispatcher == null ? new Dispatcher(null, 0, null, null) : dispatcher;
    }

    /**
     * @param retryDelays one retry after each delay; the row is {@code FAILED}
     *                    when the last retry fails (ADR-0051 decision 4)
     */
    public record Dispatcher(Duration interval, int batchSize, Duration lease, List<Duration> retryDelays) {

        public Dispatcher {
            interval = interval == null ? Duration.ofSeconds(60) : interval;
            batchSize = batchSize < 1 ? 25 : batchSize;
            lease = lease == null ? Duration.ofMinutes(2) : lease;
            retryDelays = retryDelays == null || retryDelays.isEmpty()
                    ? List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15))
                    : List.copyOf(retryDelays);
        }
    }
}
