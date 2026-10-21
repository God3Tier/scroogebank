package bank.engagement.notifications;

import java.util.Set;

public enum MailKind {
    INVITE,
    UPDATE,
    CANCEL,
    REMINDER;

    /** The kinds that carry a calendar entry (ADR-0052). */
    public static final Set<MailKind> CALENDAR = Set.of(INVITE, UPDATE, CANCEL);

    public boolean isCalendar() {
        return CALENDAR.contains(this);
    }
}
