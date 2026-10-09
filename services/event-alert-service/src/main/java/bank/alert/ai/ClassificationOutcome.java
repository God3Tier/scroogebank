package bank.alert.ai;

public record ClassificationOutcome(ClassificationStatus status, EventClassification classification,
        String reasonCode, int attempts) {
    public static ClassificationOutcome abstained(String reason, int attempts) {
        return new ClassificationOutcome(ClassificationStatus.ABSTAINED, null, reason, attempts);
    }
}
