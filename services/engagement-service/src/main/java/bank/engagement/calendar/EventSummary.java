package bank.engagement.calendar;

import java.time.Instant;
import java.util.UUID;

/** What other modules may know about an event: no title, description or location. */
public record EventSummary(UUID id, UUID clientId, Instant startsAt, Instant endsAt, EventStatus status) {
}
