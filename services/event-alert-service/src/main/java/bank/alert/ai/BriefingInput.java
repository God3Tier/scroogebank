package bank.alert.ai;

import java.util.List;

public record BriefingInput(EventClassification classification, List<PublicEvidence> evidence) {
    public BriefingInput {
        if (classification == null) throw new IllegalArgumentException("classification is required");
        evidence = List.copyOf(evidence);
        if (evidence.isEmpty()) throw new IllegalArgumentException("evidence cannot be empty");
    }
}
