package bank.engagement.holidays;

import java.time.LocalDate;

/**
 * The contract's {@code HolidayCoverage}: the last day the holiday list
 * covers, the end of the latest year it has, or null when it has none
 * (ADR-0057).
 */
public record HolidayCoverage(String countryCode, LocalDate coveredThrough) {
}
