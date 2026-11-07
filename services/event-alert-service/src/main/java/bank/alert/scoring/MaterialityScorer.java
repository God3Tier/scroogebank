package bank.alert.scoring;

import bank.alert.domain.ValuationFreshness;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class MaterialityScorer {

    public static final String RULE_VERSION = "materiality-v1";
    public static final BigDecimal ALERT_THRESHOLD = new BigDecimal("60.00");

    /** The most the event severity can add to a client's score. */
    public static final BigDecimal SEVERITY_WEIGHT = new BigDecimal("30.00");
    private static final BigDecimal EXPOSURE_WEIGHT = new BigDecimal("35.00");
    private static final BigDecimal RELEVANCE_WEIGHT = new BigDecimal("15.00");
    private static final BigDecimal CONFIDENCE_WEIGHT = new BigDecimal("20.00");
    private static final BigDecimal STALE_PENALTY = new BigDecimal("10.00");

    public MaterialityScore score(MaterialityInputs inputs) {
        if (inputs.valuationFreshness() == ValuationFreshness.EXPIRED
                || inputs.valuationFreshness() == ValuationFreshness.MISSING) {
            throw new UnevaluableMaterialityException(
                    "missing or expired valuations cannot produce an alert score");
        }

        List<ScoreFactor> factors = new ArrayList<>();
        factors.add(factor("EVENT_SEVERITY", inputs.eventSeverity(), SEVERITY_WEIGHT));
        factors.add(factor("PORTFOLIO_EXPOSURE", inputs.exposureRatio(), EXPOSURE_WEIGHT));
        factors.add(factor("RETRIEVAL_RELEVANCE", inputs.retrievalRelevance(), RELEVANCE_WEIGHT));
        factors.add(factor("CLASSIFIER_CONFIDENCE", inputs.classifierConfidence(), CONFIDENCE_WEIGHT));

        String warning = null;
        if (inputs.valuationFreshness() == ValuationFreshness.STALE) {
            factors.add(new ScoreFactor(
                    "STALE_VALUATION_PENALTY", STALE_PENALTY.negate(),
                    "Valuation is older than 24 hours"));
            warning = "Score reduced because valuation data is stale";
        }

        BigDecimal score = factors.stream()
                .map(ScoreFactor::contribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return new MaterialityScore(
                RULE_VERSION, score, ALERT_THRESHOLD,
                score.compareTo(ALERT_THRESHOLD) >= 0, factors, warning);
    }

    private static ScoreFactor factor(String code, BigDecimal input, BigDecimal weight) {
        BigDecimal contribution = input.multiply(weight).setScale(2, RoundingMode.HALF_UP);
        return new ScoreFactor(code, contribution, code + " normalized input × configured weight");
    }
}
