package bank.dashboard.exception;

/**
 * The requested reporting window is unusable -- inverted, unparseable, or
 * longer than the permitted span. Raised before any upstream call is made.
 */
public class InvalidWindowException extends RuntimeException {

    public InvalidWindowException(String message) {
        super(message);
    }
}
