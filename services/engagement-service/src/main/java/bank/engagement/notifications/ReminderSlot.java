package bank.engagement.notifications;

import java.time.Instant;

/** One reminder to queue: minutes before the meeting, and when that is. */
public record ReminderSlot(int offsetMinutes, Instant dueAt) {
}
