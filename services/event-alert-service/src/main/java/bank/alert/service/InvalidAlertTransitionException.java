package bank.alert.service;

public class InvalidAlertTransitionException extends RuntimeException {

    public InvalidAlertTransitionException(String message) {
        super(message);
    }
}
