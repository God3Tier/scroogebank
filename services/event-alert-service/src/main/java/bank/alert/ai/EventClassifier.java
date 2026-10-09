package bank.alert.ai;

public interface EventClassifier {
    ClassificationOutcome classify(PublicEvidence evidence);
}
