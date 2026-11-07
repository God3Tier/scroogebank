package bank.alert.processing;

import bank.alert.ai.BriefingStatus;
import bank.alert.ai.ClassificationStatus;

public record SourceProcessingOutcome(
        ClassificationStatus classificationStatus,
        BriefingStatus briefingStatus,
        String reasonCode,
        AlertGenerationOutcome generation) {
}
