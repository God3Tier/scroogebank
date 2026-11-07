package bank.alert.domain;

/**
 * What an agent can choose for a client: "Plan client briefing" ({@code INFORM_CLIENT}) or "Dismiss". {@code MONITOR}
 * is history only: old decisions that name it stay readable, and a new one is refused.
 */
public enum AlertDecisionType {
    INFORM_CLIENT,
    /** History only; refused for new decisions. */
    MONITOR,
    DISMISS;

    /** Whether an agent may record this decision now. */
    public boolean accepted() {
        return this != MONITOR;
    }
}
