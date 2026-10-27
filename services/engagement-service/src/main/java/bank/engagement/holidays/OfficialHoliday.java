package bank.engagement.holidays;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One row of an official holiday list, in the form the {@code holidays} table
 * keeps: the base name, with "(Observed)" turned into {@code observed}
 * (ADR-0057). Holiday names reach the drafting model (ADR-0054 decision 2),
 * so anything that does not look like a holiday name is dropped.
 */
record OfficialHoliday(LocalDate date, String name, boolean observed) {

    static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    static final LocalDate LATEST = LocalDate.of(2100, 12, 31);

    private static final Pattern OBSERVED = Pattern.compile("(?i)^(.*?)\\s*\\(observed\\)$");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} '().,&-]{1,100}");

    /** Empty when the date or name is not one the table should hold. */
    static Optional<OfficialHoliday> parse(String date, String holiday) {
        if (date == null || holiday == null) {
            return Optional.empty();
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(date.strip());
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
        if (parsed.isBefore(EARLIEST) || parsed.isAfter(LATEST)) {
            return Optional.empty();
        }
        String name = holiday.replace('’', '\'').replace('‘', '\'').strip().replaceAll("\\s+", " ");
        boolean observed = false;
        Matcher matcher = OBSERVED.matcher(name);
        if (matcher.matches()) {
            name = matcher.group(1).strip();
            observed = true;
        }
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return Optional.of(new OfficialHoliday(parsed, name, observed));
    }
}
