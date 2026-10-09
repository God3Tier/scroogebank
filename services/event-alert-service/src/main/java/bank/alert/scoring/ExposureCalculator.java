package bank.alert.scoring;

import bank.alert.domain.HoldingSnapshot;
import bank.alert.domain.ValuationFreshness;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class ExposureCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final Duration MAX_VALUATION_SPREAD = Duration.ofHours(24);

    public ExposureResult calculate(List<HoldingSnapshot> portfolio, Set<String> affectedInstrumentIds) {
        if (portfolio == null || portfolio.isEmpty()) {
            return ExposureResult.unavailable(
                    ExposureStatus.MISSING_PORTFOLIO_TOTAL, "portfolio total is unavailable");
        }
        if (affectedInstrumentIds == null || affectedInstrumentIds.isEmpty()) {
            return ExposureResult.unavailable(
                    ExposureStatus.MISSING_HOLDING, "no affected instrument was supplied");
        }
        HoldingSnapshot scope = portfolio.getFirst();
        if (portfolio.stream().anyMatch(position -> !scope.getClientRef().equals(position.getClientRef())
                || !scope.getAccountRef().equals(position.getAccountRef())
                || !scope.getAssignedAgentSubject().equals(position.getAssignedAgentSubject()))) {
            return ExposureResult.unavailable(
                    ExposureStatus.MIXED_PORTFOLIO_SCOPE,
                    "positions from different client, account, or agent scopes cannot be combined");
        }
        if (portfolio.stream().anyMatch(position -> position.getFreshness() == ValuationFreshness.EXPIRED
                || position.getFreshness() == ValuationFreshness.MISSING)) {
            return ExposureResult.unavailable(
                    ExposureStatus.EXPIRED_VALUATION, "portfolio contains expired or missing valuation data");
        }
        if (portfolio.stream().anyMatch(position -> !hasValidDerivedValue(position))) {
            return ExposureResult.unavailable(
                    ExposureStatus.INVALID_VALUATION, "a position value does not match quantity, price, and FX");
        }

        List<HoldingSnapshot> affected = portfolio.stream()
                .filter(position -> affectedInstrumentIds.contains(position.getInstrumentId()))
                .toList();
        if (affected.isEmpty()) {
            return ExposureResult.unavailable(
                    ExposureStatus.MISSING_HOLDING, "portfolio has no affected holding");
        }

        Instant oldest = portfolio.stream().map(HoldingSnapshot::getValuedAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant newest = portfolio.stream().map(HoldingSnapshot::getValuedAt).max(Comparator.naturalOrder()).orElseThrow();
        if (Duration.between(oldest, newest).compareTo(MAX_VALUATION_SPREAD) > 0) {
            return ExposureResult.unavailable(
                    ExposureStatus.INCOMPATIBLE_VALUATION_TIMES,
                    "portfolio valuations are more than 24 hours apart");
        }

        BigDecimal total = sumValues(portfolio);
        if (total.signum() <= 0) {
            return ExposureResult.unavailable(
                    ExposureStatus.MISSING_PORTFOLIO_TOTAL, "portfolio total must be positive");
        }
        BigDecimal amount = sumValues(affected);
        BigDecimal percentage = amount.multiply(ONE_HUNDRED)
                .divide(total, 4, RoundingMode.HALF_UP);
        ValuationFreshness freshness = portfolio.stream()
                .map(HoldingSnapshot::getFreshness)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElseThrow();
        String warning = freshness == ValuationFreshness.STALE
                ? "Exposure uses valuation data older than 24 hours"
                : null;
        return new ExposureResult(
                ExposureStatus.EVALUABLE, amount.setScale(4, RoundingMode.HALF_UP),
                total.setScale(4, RoundingMode.HALF_UP), percentage, newest, freshness, warning);
    }

    private static boolean hasValidDerivedValue(HoldingSnapshot position) {
        BigDecimal derived = position.getQuantity()
                .multiply(position.getUnitPrice())
                .multiply(position.getFxToSgd())
                .setScale(4, RoundingMode.HALF_UP);
        return derived.compareTo(position.getValueSgd().setScale(4, RoundingMode.HALF_UP)) == 0;
    }

    private static BigDecimal sumValues(List<HoldingSnapshot> positions) {
        return positions.stream()
                .map(HoldingSnapshot::getValueSgd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
