package bank.engagement.drafting;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The contract's {@code DraftInput}. */
public record DraftInput(@NotNull UUID clientId, @NotNull DraftPurpose purpose, @NotNull DraftTone tone,
        UUID eventId) {
}
