package bank.engagement.drafting;

import bank.engagement.holidays.HolidayView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code Draft}. {@code subject} and {@code body} are what the
 * agent edits and the client receives, with the names filled in; they are null
 * when the names cannot be looked up, and in lists. The templates are the
 * stored form, with placeholders (ADR-0056). All four are null once the draft
 * is sent or discarded (ADR-0068 decision 4).
 */
public record DraftView(
        UUID id,
        UUID clientId,
        UUID eventId,
        String healthTaskId,
        UUID alertId,
        DraftPurpose purpose,
        DraftTone tone,
        String subject,
        String body,
        String subjectTemplate,
        String bodyTemplate,
        boolean edited,
        DraftSource source,
        String sourceReason,
        HolidayView holiday,
        DraftStatus status,
        Instant sentAt,
        int version,
        Instant createdAt,
        Instant updatedAt,
        NewsView news) {

    /** Subject and body with placeholders filled in; never stored. */
    record Filled(String subject, String body) {
    }

    /**
     * A News Alert draft's reports and what is added to the email at send time (news-alert-drafts plan 5.5). Null
     * for other drafts, and in lists.
     *
     * @param summaryEdited    the agent changed the summary the service built
     * @param rebuildSuggested the reports changed after the agent edited the summary: offer "Rebuild summary?"
     * @param guide            the read-only guide to the PDF; page numbers are added from the PDF when it is sent
     * @param fixedText        the portfolio line, disclaimer and relationship manager's details added to every email
     * @param sentOutSynced    after sending: whether the clients were moved to Sent Out
     */
    public record NewsView(List<NewsReport> reports, boolean summaryEdited, boolean rebuildSuggested, String guide,
            String fixedText, boolean sentOutSynced, int maxReports) {
    }

    public record NewsReport(UUID alertId, UUID approvalId, UUID eventId, String issuer, String headline,
            Instant newsDate, Instant validUntil) {
    }
}
