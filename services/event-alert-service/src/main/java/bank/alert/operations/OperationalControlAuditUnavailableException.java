package bank.alert.operations;

public class OperationalControlAuditUnavailableException extends RuntimeException {
    public OperationalControlAuditUnavailableException() {
        super("durable operational-control audit sink is not configured");
    }
}
