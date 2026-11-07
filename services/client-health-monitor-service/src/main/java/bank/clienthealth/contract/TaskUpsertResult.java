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
        outcome = Objects.requireNonNull(outcome, "outcome is required");
        if (outcome.suppressed()) {
            if (taskId != null || taskVersion != 0) {
                throw new IllegalArgumentException("suppressed outcomes have no task or version");
            }
        } else if (taskId == null || !IDENTIFIER_PATTERN.matcher(taskId).matches()) {
            throw new IllegalArgumentException("taskId must be a non-blank opaque identifier");
        } else if (taskVersion <= 0) {
            throw new IllegalArgumentException("taskVersion must be positive");
        }
    }

    public static TaskUpsertResult suppressed(Outcome outcome) {
        if (outcome == null || !outcome.suppressed()) {
            throw new IllegalArgumentException("a suppression outcome is required");
        }
        return new TaskUpsertResult(null, outcome, 0);
    }

    public enum Outcome {
        CREATED,
        EVIDENCE_ATTACHED,
        DUPLICATE,
        SUPPRESSED_COOLDOWN,
        SUPPRESSED_DAILY_CAP;

        public boolean suppressed() {
            return this == SUPPRESSED_COOLDOWN || this == SUPPRESSED_DAILY_CAP;
        }
    }
}
