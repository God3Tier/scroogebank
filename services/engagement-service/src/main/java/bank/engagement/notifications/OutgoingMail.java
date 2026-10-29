package bank.engagement.notifications;

import java.util.Objects;

/**
 * One message, fully resolved. It holds a real address, so it lives only for
 * the send and is never stored or logged (ADR-0053 decision 7).
 *
 * @param replyTo  nullable; the agent's address on client emails
 * @param calendar nullable; the iCalendar part of an invite (ADR-0052 decision 3)
 */
public record OutgoingMail(
        String fromName,
        String fromAddress,
        String to,
        String replyTo,
        String subject,
        String textBody,
        String htmlBody,
        CalendarPart calendar) {

    public OutgoingMail {
        Objects.requireNonNull(fromAddress);
        Objects.requireNonNull(to);
        Objects.requireNonNull(subject);
        Objects.requireNonNull(textBody);
        Objects.requireNonNull(htmlBody);
    }

    /** @param method {@code REQUEST} or {@code CANCEL} */
    public record CalendarPart(String method, String icsText) {
    }

    @Override
    public String toString() {
        return "OutgoingMail[calendar=" + (calendar != null) + "]";
    }
}
