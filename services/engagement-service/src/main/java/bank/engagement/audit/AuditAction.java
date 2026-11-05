package bank.engagement.audit;

import java.util.Locale;

/**
 * What engagement-service records (agent-engagement plan §11). The auditor
 * accepts exactly these for {@code source_service} {@code ENGAGEMENT}.
 */
public enum AuditAction {
    EVENT_CREATE,
    EVENT_UPDATE,
    EVENT_CANCEL,
    INVITE_SENT,
    INVITE_FAILED,
    REMINDER_SENT,
    REMINDER_FAILED,
    REMINDER_MISSED,
    DRAFT_GENERATE,
    DRAFT_DISCARD,
    EMAIL_SEND,
    EMAIL_SEND_FAILED,
    ALERT_REPORT_APPROVED,
    ALERT_REPORT_QUEUED,
    ALERT_REPORT_SENT,
    ALERT_REPORT_FAILED,
    PREFERENCES_UPDATE;

    static final String PREFIX = "engagement.";

    /** The outbox row's type, such as {@code engagement.event-create}. */
    String outboxType() {
        return PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static AuditAction fromOutboxType(String type) {
        if (type == null || !type.startsWith(PREFIX)) {
            throw new IllegalArgumentException("unsupported engagement audit event type: " + type);
        }
        return valueOf(type.substring(PREFIX.length()).replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
