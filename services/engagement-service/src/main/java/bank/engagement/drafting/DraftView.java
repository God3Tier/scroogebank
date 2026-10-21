package bank.engagement.drafting;

import bank.engagement.holidays.HolidayView;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's {@code Draft}. {@code subject} and {@code body} are what the
 * agent edits and the client receives, with the names filled in; they are null
 * when the names cannot be looked up, and in lists. The templates are the
 * stored form, with placeholders (ADR-0056).
 */
public record DraftView(
        UUID id,
        UUID clientId,
        UUID eventId,
        DraftPurpose purpose,
        DraftTone tone,
        String subject,
        String body,
        String subjectTemplate,
        String bodyTemplate,
        DraftSource source,
        String sourceReason,
        HolidayView holiday,
        DraftStatus status,
        Instant sentAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    /** Subject and body with placeholders filled in; never stored. */
    record Filled(String subject, String body) {
    }
}
