package bank.alert.holdings;

import bank.alert.domain.ValuationFreshness;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Component
public class ValuationFreshnessPolicy {

    private static final Duration FRESH_LIMIT = Duration.ofHours(24);
    private static final Duration STALE_LIMIT = Duration.ofHours(72);

    public ValuationFreshness classify(Instant valuedAt, Instant asOf) {
        Objects.requireNonNull(valuedAt);
        Objects.requireNonNull(asOf);
        Duration age = Duration.between(valuedAt, asOf);
        if (age.isNegative()) {
            throw new IllegalArgumentException("valuation timestamp cannot be after asOf");
        }
        if (age.compareTo(FRESH_LIMIT) <= 0) {
            return ValuationFreshness.FRESH;
        }
        if (age.compareTo(STALE_LIMIT) <= 0) {
            return ValuationFreshness.STALE;
        }
        return ValuationFreshness.EXPIRED;
    }
}
