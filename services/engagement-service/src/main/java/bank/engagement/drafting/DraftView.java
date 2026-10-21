package bank.engagement.drafting;

import bank.engagement.holidays.HolidayView;

import java.time.Instant;
import java.util.UUID;

/** The contract's {@code Draft}. */
public record DraftView(
        UUID id,
        UUID clientId,
        UUID eventId,
        DraftPurpose purpose,
        DraftTone tone,
        String subjectTemplate,
        String bodyTemplate,
        Preview preview,
        DraftSource source,
        String sourceReason,
        HolidayView holiday,
        DraftStatus status,
        Instant sentAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    /** Subject and body with placeholders filled in; never stored. */
    public record Preview(String subject, String body) {
    }
}
