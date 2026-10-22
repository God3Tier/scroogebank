package bank.clienthealth.task.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class RelationshipTaskActionRequest {
    private RelationshipTaskActionRequest() { }

    public record Accept(@Positive long expectedVersion) { }

    public record Complete(
            @Positive long expectedVersion,
            @NotBlank @Size(max = 128) String outcomeCode,
            String note
    ) { }

    public record Dismiss(
            @Positive long expectedVersion,
            @NotBlank @Size(max = 128) String reasonCode,
            String note
    ) { }
}
