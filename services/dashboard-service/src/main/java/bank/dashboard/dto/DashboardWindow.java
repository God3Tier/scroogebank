package bank.dashboard.dto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The reporting window, inclusive at both ends.
 */
public record DashboardWindow(LocalDate from, LocalDate to) {

    /** Number of days covered, counting both endpoints. */
    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }
}
