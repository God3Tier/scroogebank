package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

/**
 * When the automatic searches run (ADR-0081): every {@code interval}, counted from midnight in
 * {@code zone}. The schedule itself belongs to EventBridge Scheduler in stacks/alert-worker; these
 * values only let the page show the next one, so keep them in step.
 */
@ConfigurationProperties(prefix = "alert.schedule")
public record ScheduleProperties(Boolean enabled, Duration interval, String zone) {

    public ScheduleProperties {
        enabled = enabled == null || enabled;
        interval = interval == null || interval.isZero() || interval.isNegative() ? Duration.ofHours(4) : interval;
        zone = zone == null || zone.isBlank() ? "Asia/Singapore" : zone.strip();
        ZoneId.of(zone);
    }
}
