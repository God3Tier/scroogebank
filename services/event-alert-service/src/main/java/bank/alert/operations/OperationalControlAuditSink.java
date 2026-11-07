package bank.alert.operations;

@FunctionalInterface
public interface OperationalControlAuditSink {
    void record(ControlAuditEvent event);
}
