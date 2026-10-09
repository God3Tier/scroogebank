package bank.dashboard.upstream.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day of a series.
 *
 * @param value the day's amount where the series has one, otherwise null
 */
public record DailyCount(LocalDate date, long count, BigDecimal value) {
}
