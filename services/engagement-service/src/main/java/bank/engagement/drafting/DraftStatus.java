package bank.engagement.drafting;

/**
 * {@code SENDING} is internal: it holds a draft while one send is in flight,
 * so it is sent at most once. The API shows it as {@code DRAFT}.
 */
public enum DraftStatus {
    DRAFT,
    SENDING,
    SENT,
    DISCARDED;

    public DraftStatus visible() {
        return this == SENDING ? DRAFT : this;
    }
}
