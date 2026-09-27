package bank.alert.service;

public class AlertMutationConflictException extends RuntimeException {

    public AlertMutationConflictException(String message) {
        super(message);
    }
}
