package bank.alert.processing;

import bank.alert.ai.BriefingStatus;
import bank.alert.ai.ClassificationStatus;

/** What one source result produced across all the agents it was fanned out to. */
public record SourceFanOutOutcome(
        ClassificationStatus classificationStatus,
        BriefingStatus briefingStatus,
        String reasonCode,
        int agents,
        int alertsCreated,
        int duplicates,
        int belowThreshold,
        int unevaluable,
        int dailyLimitSuppressed) {

    static SourceFanOutOutcome notAccepted(ClassificationStatus status, String reasonCode) {
        return new SourceFanOutOutcome(status, null, reasonCode, 0, 0, 0, 0, 0, 0);
    }

    public boolean accepted() {
        return classificationStatus == ClassificationStatus.MODEL_ACCEPTED;
    }
}
