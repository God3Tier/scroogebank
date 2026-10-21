package bank.engagement.drafting;

import jakarta.validation.constraints.NotNull;

/** The version the agent last saw, so they send what they reviewed. */
public record SendRequest(@NotNull Integer version) {
}
