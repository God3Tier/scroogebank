package bank.clienthealth.explanation;

import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import java.util.List;

public record ExplanationResult(
        String schemaVersion, String summary, String suggestedReviewStep,
        List<SignalType> evidenceCodes, String disclaimer, Source source, String modelId
) {
    public static final String DISCLAIMER = "Human review required";

    public ExplanationResult {
        evidenceCodes = List.copyOf(evidenceCodes);
    }

    public String displayText() {
        return String.join(" ", summary, suggestedReviewStep, disclaimer + ".");
    }

    public enum Source { BEDROCK, TEMPLATE_FALLBACK }
}
