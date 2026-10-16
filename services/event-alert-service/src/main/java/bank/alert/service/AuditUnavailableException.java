package bank.alert.service;

public class AuditUnavailableException extends RuntimeException {

    public AuditUnavailableException(String message) {
        super(message);
    }

    public AuditUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
