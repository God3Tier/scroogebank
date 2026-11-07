package bank.engagement.notifications;

import jakarta.validation.constraints.NotNull;

/** The contract's {@code Preferences}. Both are on until the agent saves otherwise. */
public record Preferences(@NotNull Boolean invitesEnabled, @NotNull Boolean remindersEnabled) {

    public static final Preferences DEFAULTS = new Preferences(true, true);
}
