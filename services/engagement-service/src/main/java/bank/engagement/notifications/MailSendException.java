package bank.engagement.notifications;

/**
 * A message the channel did not accept.
 *
 * @see #retryable() whether trying the same message later may work
 */
public class MailSendException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public MailSendException(String code, boolean retryable) {
        super("mail not accepted: " + code);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
