package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * An operator's request to replay one historical window instead of the scheduled
 * one. Set by starting a one-off worker task with these variables
 * (ALERT_WORKER_REPLAY_FROM and so on); AWS authenticates who can start that task
 * and CloudTrail records it. All four values are then required, so a replay
 * always names a person and a correlation ID (ADR-0049).
 */
@ConfigurationProperties(prefix = "alert.worker.replay")
public record ReplayProperties(String from, String to, String operator, String correlationId) {

    public ReplayProperties {
        from = blankToNull(from);
        to = blankToNull(to);
        operator = blankToNull(operator);
        correlationId = blankToNull(correlationId);
    }

    /** True when any replay value is set, so a half-filled request fails closed. */
    public boolean requested() {
        return from != null || to != null || operator != null || correlationId != null;
    }

    public Instant windowFrom() {
        return parse(from, "from");
    }

    public Instant windowTo() {
        return parse(to, "to");
    }

    /** Throws IllegalArgumentException unless the operator, correlation ID and both bounds are valid. */
    public void validate() {
        requireText(operator, "operator");
        requireText(correlationId, "correlation id");
        Instant start = windowFrom();
        Instant end = windowTo();
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("replay window start must precede its end");
        }
    }

    private static Instant parse(String value, String field) {
        if (value == null) throw new IllegalArgumentException("replay " + field + " is required");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("replay " + field + " must be an ISO-8601 UTC instant");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.length() > 128) {
            throw new IllegalArgumentException("replay " + field + " must contain 1 to 128 characters");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
