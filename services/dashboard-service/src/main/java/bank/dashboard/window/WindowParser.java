package bank.dashboard.window;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.exception.InvalidWindowException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Turns the {@code from} and {@code to} query parameters into a window, or
 * refuses.
 *
 * <p>Validation happens before any upstream call, so a nonsensical window
 * costs nothing: no aggregate query runs, and the caller gets a 400 rather
 * than a page of empty panels that looks like an outage.
 */
@Component
@RequiredArgsConstructor
public class WindowParser {

    /** The bank's business day: every service counts days in Singapore time (ADR-0038). */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Singapore");

    private final DashboardProperties properties;
    private final Clock clock;

    /**
     * @param from ISO date, or null for {@code to} minus the default span
     * @param to   ISO date, or null for today in Singapore
     */
    public DashboardWindow parse(String from, String to) {
        LocalDate end = to == null ? LocalDate.now(clock.withZone(BUSINESS_ZONE)) : parseDate(to, "to");
        // Inclusive of both endpoints, so a 30-day default covers 30 days and
        // not 31. Off-by-one here would quietly shift every windowed figure.
        LocalDate start = from == null
                ? end.minusDays(properties.window().defaultDays() - 1L)
                : parseDate(from, "from");

        if (start.isAfter(end)) {
            throw new InvalidWindowException("'from' must not be after 'to'");
        }

        DashboardWindow window = new DashboardWindow(start, end);
        if (window.days() > properties.window().maxDays()) {
            throw new InvalidWindowException(
                    "window must not exceed " + properties.window().maxDays() + " days");
        }
        return window;
    }

    private LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new InvalidWindowException("'" + field + "' is not a valid ISO date: " + value);
        }
    }
}
