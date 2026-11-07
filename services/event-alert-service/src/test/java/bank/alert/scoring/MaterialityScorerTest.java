package bank.alert.scoring;

import bank.alert.domain.ValuationFreshness;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterialityScorerTest {

    private final MaterialityScorer scorer = new MaterialityScorer();

    @Test
    void producesReproducibleVersionedScore() {
        MaterialityInputs inputs = inputs(ValuationFreshness.FRESH);

        MaterialityScore first = scorer.score(inputs);
        MaterialityScore replay = scorer.score(inputs);

        assertThat(first).isEqualTo(replay);
        assertThat(first.ruleVersion()).isEqualTo("materiality-v1");
        assertThat(first.score()).isEqualByComparingTo("70.00");
        assertThat(first.alertEligible()).isTrue();
        assertThat(first.factors()).hasSize(4);
    }

    @Test
    void aSmallHoldingScoresLowButStillMakesAnAlert() {
        MaterialityScore small = scorer.score(new MaterialityInputs(new BigDecimal("0.60"), new BigDecimal("0.10"),
                new BigDecimal("0.80"), new BigDecimal("0.90"), ValuationFreshness.FRESH));

        assertThat(small.score()).isLessThan(new BigDecimal("60.00"));
        assertThat(small.alertEligible()).isTrue();
        assertThat(small.threshold()).isEqualByComparingTo("0.00");
    }

    @Test
    void appliesDeterministicStalePenalty() {
        MaterialityScore result = scorer.score(inputs(ValuationFreshness.STALE));

        assertThat(result.score()).isEqualByComparingTo("60.00");
        assertThat(result.alertEligible()).isTrue();
        assertThat(result.warning()).contains("stale");
        assertThat(result.factors()).extracting(ScoreFactor::code)
                .contains("STALE_VALUATION_PENALTY");
    }

    @Test
    void refusesMissingOrExpiredValuations() {
        assertThatThrownBy(() -> scorer.score(inputs(ValuationFreshness.EXPIRED)))
                .isInstanceOf(UnevaluableMaterialityException.class);
        assertThatThrownBy(() -> scorer.score(inputs(ValuationFreshness.MISSING)))
                .isInstanceOf(UnevaluableMaterialityException.class);
    }

    @Test
    void validatesNormalizedInputs() {
        assertThatThrownBy(() -> new MaterialityInputs(
                new BigDecimal("1.01"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, ValuationFreshness.FRESH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MaterialityInputs inputs(ValuationFreshness freshness) {
        return new MaterialityInputs(
                new BigDecimal("0.80"), new BigDecimal("0.50"),
                new BigDecimal("0.90"), new BigDecimal("0.75"), freshness);
    }
}
