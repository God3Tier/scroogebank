package bank.engagement.holidays;

import bank.engagement.config.TimeConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Adds the holidays an official source lists that the table does not have yet
 * (ADR-0057). Rows are only ever added, never changed or removed, because
 * drafts refer to them; a row the source no longer lists is logged as
 * {@code holiday_sync_mismatch} for a person to correct with a migration.
 * Two tasks may sync at once: the table's unique key makes the second insert
 * of a row a no-op.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HolidaySync {

    /** MOM publishes next year's list around July, so from October its absence is worth an alarm. */
    static final int COVERAGE_EXPECTED_FROM_MONTH = 10;

    private static final String INSERT = """
            INSERT INTO holidays (id, country_code, holiday_date, name, observed, source, dataset_version)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (country_code, holiday_date, name) DO NOTHING
            """;

    private final HolidaySource source;
    private final HolidayService holidays;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** What one run did. {@code coveredThrough} is null when the country has no holidays at all. */
    public record Result(boolean fetched, int added, LocalDate coveredThrough, boolean coverageShort) {
    }

    public Result sync() {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfiguration.SINGAPORE));
        List<OfficialHoliday> listed;
        try {
            listed = source.fetch();
        } catch (HolidaySourceException ex) {
            log.warn("holiday_sync_failed: {}", ex.getMessage());
            return coverage(today, false, 0);
        }

        String version = "data.gov.sg-" + today;
        int[] inserted = jdbc.batchUpdate(INSERT, listed.stream()
                .map(holiday -> new Object[] {UUID.randomUUID(), source.countryCode(), Date.valueOf(holiday.date()),
                        holiday.name(), holiday.observed(), source.sourceUrl(), version})
                .toList());
        int added = 0;
        for (int count : inserted) {
            added += Math.max(count, 0);
        }
        reportMismatches(listed);
        log.info("holiday_sync country={} listed={} added={}", source.countryCode(), listed.size(), added);
        return coverage(today, true, added);
    }

    private Result coverage(LocalDate today, boolean fetched, int added) {
        LocalDate coveredThrough = holidays.coveredThrough(source.countryCode()).orElse(null);
        boolean expectNextYear = today.getMonthValue() >= COVERAGE_EXPECTED_FROM_MONTH;
        int needed = expectNextYear ? today.getYear() + 1 : today.getYear();
        boolean coverageShort = coveredThrough == null || coveredThrough.getYear() < needed;
        if (coverageShort) {
            log.warn("holiday_coverage_short: no {} public holidays for {} yet", source.countryCode(), needed);
        }
        return new Result(fetched, added, coveredThrough, coverageShort);
    }

    /** Rows in the years the source covers that it does not list (dates and names are public). */
    private void reportMismatches(List<OfficialHoliday> listed) {
        if (listed.isEmpty()) {
            return;
        }
        LocalDate from = listed.stream().map(OfficialHoliday::date).min(Comparator.naturalOrder()).orElseThrow()
                .withDayOfYear(1);
        LocalDate to = listed.stream().map(OfficialHoliday::date).max(Comparator.naturalOrder()).orElseThrow()
                .withMonth(12).withDayOfMonth(31);
        Set<String> keys = new HashSet<>();
        listed.forEach(holiday -> keys.add(holiday.date() + "|" + holiday.name()));
        List<String> unlisted = jdbc.queryForList("""
                        SELECT holiday_date || '|' || name FROM holidays
                        WHERE country_code = ? AND holiday_date BETWEEN ? AND ?
                        """, String.class, source.countryCode(), Date.valueOf(from), Date.valueOf(to))
                .stream()
                .filter(key -> !keys.contains(key))
                .sorted()
                .toList();
        if (!unlisted.isEmpty()) {
            log.warn("holiday_sync_mismatch: {} rows the source does not list: {}", unlisted.size(), unlisted);
        }
    }
}
