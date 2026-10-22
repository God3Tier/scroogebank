package bank.engagement.holidays;

import bank.engagement.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HolidayService {

    static final int MAX_RANGE_DAYS = 366;

    private final HolidayRepository repository;

    /** {@code GET /api/engagement/holidays}: an inclusive range of at most 366 days. */
    public List<HolidayView> list(String countryCode, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("'to' must not be before 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("The range must be at most " + MAX_RANGE_DAYS + " days");
        }
        return find(countryCode, from, to);
    }

    /** Up to {@code limit} holidays from {@code from} for {@code days} days, nearest first. */
    public List<HolidayView> upcoming(String countryCode, LocalDate from, int days, int limit) {
        return find(countryCode, from, from.plusDays(days)).stream().limit(limit).toList();
    }

    /**
     * The last day of the last year with any holidays for the country: the
     * calendar does not offer months past it (ADR-0057). Empty when there are none.
     */
    public Optional<LocalDate> coveredThrough(String countryCode) {
        return repository.findLastDate(countryCode).map(last -> LocalDate.of(last.getYear(), 12, 31));
    }

    public Optional<HolidayView> find(UUID id) {
        return id == null ? Optional.empty() : repository.findById(id).map(Holiday::view);
    }

    private List<HolidayView> find(String countryCode, LocalDate from, LocalDate to) {
        return repository.findByCountryCodeAndHolidayDateBetweenOrderByHolidayDateAscNameAsc(countryCode, from, to)
                .stream()
                .map(Holiday::view)
                .toList();
    }
}
