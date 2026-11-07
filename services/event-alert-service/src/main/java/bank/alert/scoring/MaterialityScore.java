package bank.alert.scoring;

import java.math.BigDecimal;
import java.util.List;

public record MaterialityScore(
        String ruleVersion,
        BigDecimal score,
        BigDecimal threshold,
        boolean alertEligible,
        List<ScoreFactor> factors,
        String warning) {

    public MaterialityScore {
        factors = List.copyOf(factors);
    }
}
