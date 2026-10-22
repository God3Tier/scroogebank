package bank.engagement.holidays;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the holiday sync soon after start-up and then daily (ADR-0057). The
 * stack is stopped between sessions, so the start-up run is the one that
 * usually picks up a newly published year.
 */
@Component
@ConditionalOnProperty(prefix = "engagement.holidays.sync", name = "enabled", havingValue = "true")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
class HolidaySyncScheduler {

    private final HolidaySync sync;

    @Scheduled(initialDelayString = "${engagement.holidays.sync.initial-delay:1m}",
            fixedDelayString = "${engagement.holidays.sync.interval:24h}")
    void run() {
        try {
            sync.sync();
        } catch (RuntimeException ex) {
            log.warn("holiday_sync_failed: {}", ex.getClass().getSimpleName());
        }
    }
}
