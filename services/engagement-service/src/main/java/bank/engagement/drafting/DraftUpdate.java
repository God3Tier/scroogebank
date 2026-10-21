package bank.engagement.drafting;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The contract's {@code DraftUpdate}: plain text with the names written out.
 * The service marks the names again before storing it (ADR-0056).
 */
public record DraftUpdate(
        @NotBlank @Size(max = 120) String subject,
        @NotBlank @Size(max = 5000) String body,
        @NotNull @Min(0) Integer version) {
}
