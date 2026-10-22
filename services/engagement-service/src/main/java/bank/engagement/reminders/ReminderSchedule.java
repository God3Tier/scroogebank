package bank.engagement.reminders;

import bank.engagement.notifications.MailQueue;
import bank.engagement.notifications.ReminderSlot;
import bank.engagement.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * When an event's reminders fall due, and rescheduling them when the event
 * changes (agent-engagement plan §6).
 */
@Component
@RequiredArgsConstructor
public class ReminderSchedule {

    /** The offsets the contract allows: 15 minutes, 30 minutes, 1 hour and 1 day. */
    public static final Set<Integer> ALLOWED_OFFSETS = Set.of(15, 30, 60, 1440);

    static final int MAX_REMINDERS = 2;

    private final MailQueue mailQueue;

    /** @throws ApiException 400 for an offset the contract does not allow, a repeat, or more than two */
    public static List<Integer> validate(List<Integer> offsets) {
        List<Integer> requested = offsets == null ? List.of() : offsets;
        if (requested.size() > MAX_REMINDERS) {
            throw ApiException.badRequest("reminderOffsetsMinutes allows at most " + MAX_REMINDERS + " reminders");
        }
        Set<Integer> seen = new HashSet<>();
        for (Integer offset : requested) {
            if (offset == null || !ALLOWED_OFFSETS.contains(offset)) {
                throw ApiException.badRequest("reminderOffsetsMinutes must be 15, 30, 60 or 1440");
            }
            if (!seen.add(offset)) {
                throw ApiException.badRequest("reminderOffsetsMinutes must not repeat an offset");
            }
        }
        return requested.stream().sorted(Comparator.reverseOrder()).toList();
    }

    public static List<ReminderSlot> slots(Instant startsAt, List<Integer> offsets) {
        return offsets.stream()
                .map(offset -> new ReminderSlot(offset, startsAt.minus(Duration.ofMinutes(offset))))
                .sorted(Comparator.comparing(ReminderSlot::dueAt))
                .toList();
    }

    /** Replaces the event's pending reminders with ones for its current time and offsets. */
    @Transactional
    public void reschedule(UUID eventId, String owner, int eventVersion, Instant startsAt, List<Integer> offsets,
            boolean remindersEnabled, Instant now) {
        mailQueue.replaceReminders(eventId, owner, eventVersion, slots(startsAt, offsets), remindersEnabled, now);
    }

    @Transactional
    public void cancel(UUID eventId, Instant now) {
        mailQueue.cancelPendingReminders(eventId, now);
    }
}
