package bank.alert.ai;

public class AiProviderException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public AiProviderException(String code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
