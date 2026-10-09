package bank.dashboard.audit;

/**
 * Where dashboard audit events go. The only implementation sends them to the
 * shared audit queue (ADR-0033); no caller knows the transport.
 */
public interface AuditEmitter {

    /** Best-effort: must not throw, because auditing a read must not fail it. */
    void emit(DashboardAuditEvent event);
}
