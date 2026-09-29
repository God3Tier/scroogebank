package bank.clienthealth.contract;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Result returned after the task stream handles a finding.
 */
public record TaskUpsertResult(
        String taskId,
        Outcome outcome,
        long taskVersion
) {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public TaskUpsertResult {
        if (taskId == null || !IDENTIFIER_PATTERN.matcher(taskId).matches()) {
            throw new IllegalArgumentException("taskId must be a non-blank opaque identifier");
        }
        outcome = Objects.requireNonNull(outcome, "outcome is required");
        if (taskVersion <= 0) {
            throw new IllegalArgumentException("taskVersion must be positive");
        }
    }

    public enum Outcome {
        CREATED,
        EVIDENCE_ATTACHED,
        DUPLICATE
    }
}
