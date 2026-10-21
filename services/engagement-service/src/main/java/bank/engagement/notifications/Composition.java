package bank.engagement.notifications;

/** What to do with a claimed row. */
public sealed interface Composition {

    /** Send this message. */
    record Ready(OutgoingMail mail) implements Composition {
    }

    /** Do not send: the meeting has started ({@code MISSED}) or was cancelled ({@code CANCELLED}). */
    record Skip(MailStatus status) implements Composition {
        public Skip {
            if (status != MailStatus.MISSED && status != MailStatus.CANCELLED) {
                throw new IllegalArgumentException("a skipped row is MISSED or CANCELLED");
            }
        }
    }

    /** Cannot be sent at all, for example the agent has no address. */
    record Fail(String errorCode) implements Composition {
    }
}
