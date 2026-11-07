package bank.engagement.notifications;

/** No mail provider is configured ({@code ENGAGEMENT_MAIL_PROVIDER=none}). */
public class MailUnavailableException extends RuntimeException {

    public MailUnavailableException() {
        super("email sending is not configured");
    }
}
