package bank.alert.service;

import bank.alert.config.ScheduleProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchScheduleServiceTest {

    private static SearchScheduleService at(String instant, String interval) {
        ScheduleProperties properties = new ScheduleProperties(true, Duration.parse(interval), "Asia/Singapore");
        return new SearchScheduleService(properties, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void runsAtFixedSingaporeTimesEveryFourHours() {
        // 11:30 Singapore is 03:30 UTC; the runs are at 00:00, 04:00, 08:00, 12:00, 16:00 and 20:00 Singapore time.
        SearchScheduleService.Schedule schedule = at("2026-11-04T03:30:00Z", "PT4H").current();

        assertThat(schedule.previousRunAt()).isEqualTo(Instant.parse("2026-11-04T00:00:00Z")); // 08:00 Singapore
        assertThat(schedule.nextRunAt()).isEqualTo(Instant.parse("2026-11-04T04:00:00Z"));     // 12:00 Singapore
        assertThat(schedule.intervalMinutes()).isEqualTo(240);
        assertThat(schedule.zone()).isEqualTo("Asia/Singapore");
        assertThat(schedule.enabled()).isTrue();
    }

    @Test
    void exactlyOnARunTimeTheNextOneIsAFullIntervalAway() {
        SearchScheduleService.Schedule schedule = at("2026-11-04T04:00:00Z", "PT4H").current();

        assertThat(schedule.previousRunAt()).isEqualTo(Instant.parse("2026-11-04T04:00:00Z"));
        assertThat(schedule.nextRunAt()).isEqualTo(Instant.parse("2026-11-04T08:00:00Z"));
    }

    @Test
    void afterTheLastRunOfTheDayTheNextIsMidnightSingaporeTime() {
        // 21:00 Singapore (13:00 UTC): the next run is 00:00 the next day Singapore time (16:00 UTC).
        SearchScheduleService.Schedule schedule = at("2026-11-04T13:00:00Z", "PT4H").current();

        assertThat(schedule.nextRunAt()).isEqualTo(Instant.parse("2026-11-04T16:00:00Z"));
    }

    @Test
    void aShorterIntervalForADemoFollowsTheSameClock() {
        SearchScheduleService.Schedule schedule = at("2026-11-04T03:07:00Z", "PT10M").current();

        assertThat(schedule.nextRunAt()).isEqualTo(Instant.parse("2026-11-04T03:10:00Z"));
        assertThat(schedule.intervalMinutes()).isEqualTo(10);
    }

    @Test
    void anUnsetIntervalDefaultsToFourHoursAndAnUnknownZoneIsRefused() {
        assertThat(new ScheduleProperties(null, null, null).interval()).isEqualTo(Duration.ofHours(4));
        assertThat(new ScheduleProperties(null, null, null).zone()).isEqualTo("Asia/Singapore");
        assertThatThrownBy(() -> new ScheduleProperties(true, Duration.ofHours(4), "Nowhere/Land"))
                .isInstanceOf(RuntimeException.class);
    }
}
