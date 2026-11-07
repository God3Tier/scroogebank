package bank.alert.domain;

/**
 * A client's status on one report. {@code MONITORING} is history only: "Continue monitoring" was removed, existing
 * rows became {@code NEW}, and nothing sets it again, but old decisions that name it stay readable. {@code SENT_OUT}
 * is final and is set only when engagement-service has emailed the client the report.
 */
public enum AlertStatus {
    NEW,
    /** History only; never set any more. */
    MONITORING,
    INFORMED,
    DISMISSED,
    SENT_OUT
}
