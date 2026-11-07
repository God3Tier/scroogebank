package bank.engagement.notifications;

import java.util.Objects;

/**
 * One message, fully resolved. It holds a real address, so it lives only for
 * the send and is never stored or logged (ADR-0053 decision 7).
 *
 * @param replyTo  nullable; the agent's address on client emails
 * @param bcc      nullable; the agent's own copy of a client email, which is
 *                 their record of it once the draft's text is cleared (ADR-0068)
 * @param calendar nullable; the iCalendar part of an invite (ADR-0052 decision 3)
 * @param attachments files attached to the message, such as a News Alert's report PDF; empty for most mail
 */
public record OutgoingMail(
        String fromName,
        String fromAddress,
        String to,
        String replyTo,
        String bcc,
        String subject,
        String textBody,
        String htmlBody,
        CalendarPart calendar,
        java.util.List<Attachment> attachments) {

    /** A message with no Bcc: invites, reminders. */
    public OutgoingMail(String fromName, String fromAddress, String to, String replyTo, String subject,
            String textBody, String htmlBody, CalendarPart calendar) {
        this(fromName, fromAddress, to, replyTo, null, subject, textBody, htmlBody, calendar, java.util.List.of());
    }

    /** A client email with no attachment. */
    public OutgoingMail(String fromName, String fromAddress, String to, String replyTo, String bcc, String subject,
            String textBody, String htmlBody, CalendarPart calendar) {
        this(fromName, fromAddress, to, replyTo, bcc, subject, textBody, htmlBody, calendar, java.util.List.of());
    }

    public OutgoingMail {
        Objects.requireNonNull(fromAddress);
        Objects.requireNonNull(to);
        Objects.requireNonNull(subject);
        Objects.requireNonNull(textBody);
        Objects.requireNonNull(htmlBody);
        attachments = attachments == null ? java.util.List.of() : java.util.List.copyOf(attachments);
    }

    /** One attached file. */
    public record Attachment(String fileName, String contentType, byte[] content) {
        public Attachment {
            Objects.requireNonNull(fileName);
            Objects.requireNonNull(contentType);
            Objects.requireNonNull(content);
        }

        @Override
        public String toString() {
            return "Attachment[" + content.length + " bytes]";
        }
    }

    /** @param method {@code REQUEST} or {@code CANCEL} */
    public record CalendarPart(String method, String icsText) {
    }

    @Override
    public String toString() {
        return "OutgoingMail[calendar=" + (calendar != null) + ", attachments=" + attachments.size() + "]";
    }
}
