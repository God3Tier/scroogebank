package bank.engagement.drafting;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The contract's {@code DraftUpdate}. */
public record DraftUpdate(
        @NotBlank @Size(max = 120) String subjectTemplate,
        @NotBlank @Size(max = 5000) String bodyTemplate,
        @NotNull @Min(0) Integer version) {
}
