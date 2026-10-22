package bank.engagement.notifications;

import java.time.Instant;

/**
 * Builds the message for a queued row. The calendar module implements it,
 * because it owns the event the message is about.
 */
public interface MailComposer {

    Composition compose(QueuedMail mail, Instant now);
}
