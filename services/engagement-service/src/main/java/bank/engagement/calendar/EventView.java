package bank.engagement.calendar;

import bank.engagement.notifications.DeliverySummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The contract's {@code Event}. */
public record EventView(
        UUID id,
        String title,
        String description,
        String location,
        Instant startsAt,
        Instant endsAt,
        UUID clientId,
        EventStatus status,
        DeliverySummary.Invite invite,
        List<DeliverySummary.Reminder> reminders,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
