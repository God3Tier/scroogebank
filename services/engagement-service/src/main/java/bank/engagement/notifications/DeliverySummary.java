package bank.engagement.notifications;

import java.time.Instant;
import java.util.List;

/**
 * What an event shows about its mail: the latest invite, update or
 * cancellation, and the reminders queued for its current version.
 */
public record DeliverySummary(Invite invite, List<Reminder> reminders) {

    public record Invite(MailStatus status, int sequence) {
    }

    public record Reminder(int offsetMinutes, Instant dueAt, MailStatus status) {
    }
}
