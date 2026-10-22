package bank.engagement.notifications;

import java.time.Instant;
import java.util.UUID;

/** A claimed queue row, as the module that composes its message sees it. */
public record QueuedMail(
        UUID id,
        MailKind kind,
        UUID eventId,
        String ownerAgentSub,
        int offsetMinutes,
        int icsSequence,
        Instant dueAt) {
}
