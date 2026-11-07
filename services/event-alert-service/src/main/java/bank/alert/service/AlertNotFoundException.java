package bank.alert.service;

public class AlertNotFoundException extends RuntimeException {

    public AlertNotFoundException() {
        super("alert was not found");
    }
}
