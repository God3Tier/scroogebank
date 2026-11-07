package bank.engagement.reminders;

import bank.engagement.notifications.ReminderSlot;
import bank.engagement.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReminderScheduleTest {

    @Test
    void acceptsUpToTwoAllowedOffsets() {
        assertThat(ReminderSchedule.validate(List.of(15, 1440))).containsExactly(1440, 15);
        assertThat(ReminderSchedule.validate(null)).isEmpty();
        assertThat(ReminderSchedule.validate(List.of())).isEmpty();
    }

    @Test
    void rejectsOffsetsTheContractDoesNotAllow() {
        assertThatThrownBy(() -> ReminderSchedule.validate(List.of(10))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ReminderSchedule.validate(List.of(15, 30, 60))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ReminderSchedule.validate(List.of(30, 30))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ReminderSchedule.validate(Arrays.asList(15, null))).isInstanceOf(ApiException.class);
    }

    @Test
    void dueTimesAreTheOffsetsBeforeTheStart() {
        Instant start = Instant.parse("2026-10-22T06:00:00Z");

        List<ReminderSlot> slots = ReminderSchedule.slots(start, List.of(1440, 15));

        assertThat(slots).containsExactly(
                new ReminderSlot(1440, Instant.parse("2026-10-21T06:00:00Z")),
                new ReminderSlot(15, Instant.parse("2026-10-22T05:45:00Z")));
    }
}
