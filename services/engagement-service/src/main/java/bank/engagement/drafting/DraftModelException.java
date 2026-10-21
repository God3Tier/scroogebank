package bank.engagement.drafting;

/** A failed model call; {@link #reason()} is a contract {@code sourceReason}. */
public class DraftModelException extends RuntimeException {

    private final String reason;

    public DraftModelException(String reason) {
        super("model call failed: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
