package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * A request, made by starting a worker task with these variables (ALERT_WORKER_RESEARCH_INSTRUMENTS
 * and so on), to search some companies over their recent past (ADR-0081). With a {@code domain} only
 * that added website is searched; without one, every website is searched. Like a replay it never
 * moves the scheduler watermark.
 */
@ConfigurationProperties(prefix = "alert.worker.research")
public record ResearchProperties(List<String> instruments, String domain, Duration lookback, String correlationId) {

    static final Duration LONGEST_LOOKBACK = Duration.ofDays(31);
    private static final Duration SHORTEST_LOOKBACK = Duration.ofHours(1);

    public ResearchProperties {
        instruments = instruments == null ? List.of() : instruments.stream()
                .filter(id -> id != null && !id.isBlank()).map(String::strip).distinct().toList();
        domain = blankToNull(domain);
        correlationId = blankToNull(correlationId);
        lookback = lookback == null ? Duration.ofDays(14) : lookback;
    }

    /** True when any company is named, so a half-filled request fails closed. */
    public boolean requested() {
        return !instruments.isEmpty() || domain != null;
    }

    /** Throws IllegalArgumentException unless at least one company and a sensible lookback are set. */
    public void validate() {
        if (instruments.isEmpty() || instruments.size() > 200 || instruments.stream().anyMatch(id -> id.length() > 32)) {
            throw new IllegalArgumentException("research instruments must name 1 to 200 companies");
        }
        if (domain != null && domain.length() > 253) {
            throw new IllegalArgumentException("research domain must contain at most 253 characters");
        }
        if (lookback.compareTo(SHORTEST_LOOKBACK) < 0 || lookback.compareTo(LONGEST_LOOKBACK) > 0) {
            throw new IllegalArgumentException("research lookback must be between 1 hour and 31 days");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
