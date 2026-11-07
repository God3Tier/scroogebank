package bank.alert.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class RuleFallbackEvaluator {
    private static final List<String> REVIEW_TERMS = List.of(
            "earnings", "acquisition", "merger", "regulatory", "credit", "chief executive", "ceo");

    public ClassificationOutcome evaluate(PublicEvidence evidence, String reasonCode, int attempts) {
        String publicText = (evidence.title() + " " + evidence.excerpt()).toLowerCase(Locale.ROOT);
        boolean review = REVIEW_TERMS.stream().anyMatch(publicText::contains);
        return new ClassificationOutcome(review ? ClassificationStatus.RULE_REVIEW : ClassificationStatus.ABSTAINED,
                null, reasonCode, attempts);
    }
}
