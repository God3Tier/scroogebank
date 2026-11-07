package bank.engagement.integration.sources;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The approved event reports a News Alert draft carries (news-alert-drafts plan, sections 5.5 and 5.6), read from
 * event-alert-service with the calling agent's token. That service decides ownership, status and approval on every
 * call; nothing here trusts what the browser sent.
 */
public interface NewsReports {

    /** {@code outcome}: OK, NOT_AUTHORISED, NOT_INFORMED or NOT_APPROVED; the other fields are null unless OK. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PackageItem(UUID alertId, String outcome, UUID approvalId, String clientRef, String instrumentId,
            String issuer, String headline, Instant newsDate, Instant validUntil, boolean edited, String finalHash,
            String summaryLine) {

        public boolean ok() {
            return "OK".equals(outcome) && approvalId != null && clientRef != null;
        }
    }

    /** {@code outcome}: OK, NOT_AUTHORISED, NOT_INFORMED, NOT_APPROVED or EXPIRED. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CheckItem(UUID alertId, String outcome, UUID approvalId, String finalHash) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Section(UUID approvalId, String issuer, String headline, int startPage, String finalHash) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Pdf(String fileName, LocalDate documentDate, String pdfBase64, String pdfHash, int pageCount,
            List<Section> sections) {

        public Pdf {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }

        public byte[] bytes() {
            return java.util.Base64.getDecoder().decode(pdfBase64);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SentOutItem(UUID alertId, String outcome) {
    }

    /**
     * @throws SourceNotFoundException    the event is not the agent's
     * @throws SourceUnavailableException event-alert-service could not answer; callers fail closed
     */
    List<PackageItem> packageFor(UUID eventId, List<UUID> alertIds);

    List<CheckItem> sendCheck(List<UUID> alertIds);

    /** The combined PDF; {@code preparedBy} is the agent's name for its cover. */
    Pdf combinedPdf(List<UUID> approvalIds, String preparedBy);

    List<SentOutItem> sentOut(List<UUID> alertIds, UUID draftId, String sesMessageId);
}
