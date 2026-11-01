package bank.engagement.drafting;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The contract's {@code DraftInput}. {@code healthTaskId} and {@code alertId}
 * say which Client Health task or alert a follow-up came from (ADR-0068).
 */
public record DraftInput(@NotNull UUID clientId, @NotNull DraftPurpose purpose, @NotNull DraftTone tone,
        UUID eventId, @Size(min = 1, max = 128) String healthTaskId, UUID alertId) {
}
