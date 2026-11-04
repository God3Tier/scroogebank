package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * A request, made by starting a worker task with these variables (ALERT_WORKER_RESEARCH_INSTRUMENT
 * and so on), to search one company over its recent past using one added domain (ADR-0080).
 * Like a replay it never moves the scheduler watermark.
 */
@ConfigurationProperties(prefix = "alert.worker.research")
public record ResearchProperties(String instrument, String domain, Duration lookback, String correlationId) {

    static final Duration LONGEST_LOOKBACK = Duration.ofDays(31);
    private static final Duration SHORTEST_LOOKBACK = Duration.ofHours(1);

    public ResearchProperties {
        instrument = blankToNull(instrument);
        domain = blankToNull(domain);
        correlationId = blankToNull(correlationId);
        lookback = lookback == null ? Duration.ofDays(14) : lookback;
    }

    /** True when either value is set, so a half-filled request fails closed. */
    public boolean requested() {
        return instrument != null || domain != null;
    }

    /** Throws IllegalArgumentException unless the company, the domain and a sensible lookback are set. */
    public void validate() {
        if (instrument == null || instrument.length() > 32) {
            throw new IllegalArgumentException("research instrument must contain 1 to 32 characters");
        }
        if (domain == null || domain.length() > 253) {
            throw new IllegalArgumentException("research domain must contain 1 to 253 characters");
        }
        if (lookback.compareTo(SHORTEST_LOOKBACK) < 0 || lookback.compareTo(LONGEST_LOOKBACK) > 0) {
            throw new IllegalArgumentException("research lookback must be between 1 hour and 31 days");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
