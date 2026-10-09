package bank.alert.ingestion;

public class RetryExhaustedException extends RuntimeException {

    private final String code;
    private final int attempts;

    public RetryExhaustedException(String code, int attempts, Throwable cause) {
        super("search provider failed after " + attempts + " attempt(s)", cause);
        this.code = code;
        this.attempts = attempts;
    }

    public String code() { return code; }
    public int attempts() { return attempts; }
}
