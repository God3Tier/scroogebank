package bank.alert.service;

import bank.alert.config.ScheduleProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/** Works out when the next automatic search is, for the page (ADR-0081). A manual search never changes it. */
@Service
public class SearchScheduleService {

    public record Schedule(boolean enabled, long intervalMinutes, String zone, Instant previousRunAt, Instant nextRunAt) {}

    private final ScheduleProperties properties;
    private final Clock clock;

    public SearchScheduleService(ScheduleProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Schedule current() {
        ZoneId zone = ZoneId.of(properties.zone());
        ZonedDateTime now = clock.instant().atZone(zone);
        ZonedDateTime midnight = now.truncatedTo(ChronoUnit.DAYS);
        Duration interval = properties.interval();
        long elapsed = Duration.between(midnight, now).toMillis() / interval.toMillis();
        ZonedDateTime previous = midnight.plus(interval.multipliedBy(elapsed));
        ZonedDateTime next = midnight.plus(interval.multipliedBy(elapsed + 1));
        return new Schedule(properties.enabled(), interval.toMinutes(), properties.zone(), previous.toInstant(), next.toInstant());
    }
}
