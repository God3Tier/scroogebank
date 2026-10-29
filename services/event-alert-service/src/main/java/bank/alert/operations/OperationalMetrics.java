package bank.alert.operations;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class OperationalMetrics {
    private final MeterRegistry registry;
    private final AtomicLong monthlySearches = new AtomicLong();
    private final AtomicLong monthlyModelTokens = new AtomicLong();
    private final AtomicReference<Double> monthlyEstimatedCost = new AtomicReference<>(0.0);
    private final AtomicLong eligibleInstruments = new AtomicLong();
    private final AtomicLong selectedInstruments = new AtomicLong();
    private final AtomicLong deferredInstruments = new AtomicLong();
    private final AtomicLong commonSelections = new AtomicLong();
    private final AtomicLong concentrationSelections = new AtomicLong();
    private final AtomicLong oldestCoverageAgeSeconds = new AtomicLong();
    private final AtomicLong maintainedPortfolios = new AtomicLong();
    private final AtomicLong missingHoldings = new AtomicLong();
    private final AtomicLong expiredValuations = new AtomicLong();
    private final AtomicLong invalidCrmLinks = new AtomicLong();

    public OperationalMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("alert.outbound.monthly.searches", monthlySearches);
        registry.gauge("alert.outbound.monthly.model.tokens", monthlyModelTokens);
        registry.gauge("alert.outbound.monthly.estimated.cost.usd", monthlyEstimatedCost,
                AtomicReference::get);
        registry.gauge("alert.search.universe.eligible", eligibleInstruments);
        registry.gauge("alert.search.batch.selected", selectedInstruments);
        registry.gauge("alert.search.batch.deferred", deferredInstruments);
        registry.gauge("alert.search.selection.common", commonSelections);
        registry.gauge("alert.search.selection.concentration", concentrationSelections);
        registry.gauge("alert.search.coverage.oldest.age.seconds", oldestCoverageAgeSeconds);
        registry.gauge("alert.holdings.portfolios.maintained", maintainedPortfolios);
        registry.gauge("alert.holdings.positions.missing", missingHoldings);
        registry.gauge("alert.holdings.positions.expired", expiredValuations);
        registry.gauge("alert.holdings.crm.links.invalid", invalidCrmLinks);
    }

    void reservation(OutboundCapability capability, String resultCode) {
        registry.counter("alert.outbound.reservations",
                "capability", capability.name().toLowerCase(),
                "result", safeCode(resultCode)).increment();
    }

    void disabled(OutboundCapability capability, ControlReason reason) {
        registry.counter("alert.outbound.disabled",
                "capability", capability.name().toLowerCase(),
                "reason", reason.name().toLowerCase()).increment();
    }

    void reenabled(OutboundCapability capability) {
        registry.counter("alert.outbound.reenabled",
                "capability", capability.name().toLowerCase()).increment();
    }

    void usage(long searches, long modelTokens, BigDecimal estimatedCostUsd) {
        monthlySearches.set(searches);
        monthlyModelTokens.set(modelTokens);
        monthlyEstimatedCost.set(estimatedCostUsd.doubleValue());
    }

    public void searchUniverse(int eligible, int common, int concentration) {
        eligibleInstruments.set(eligible);
        commonSelections.set(common);
        concentrationSelections.set(concentration);
    }

    public void searchBatch(int selected, int deferred, long oldestAgeSeconds) {
        selectedInstruments.set(selected);
        deferredInstruments.set(deferred);
        oldestCoverageAgeSeconds.set(Math.max(0, oldestAgeSeconds));
    }

    /** Aggregate counts only, from the latest full read of the maintained portfolios. */
    public void portfolios(int maintained, int missingPositions, int expiredPositions, int invalidLinks) {
        maintainedPortfolios.set(maintained);
        missingHoldings.set(missingPositions);
        expiredValuations.set(expiredPositions);
        invalidCrmLinks.set(invalidLinks);
    }

    public void directoryFailure() {
        registry.counter("alert.holdings.crm.directory.failures").increment();
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z0-9_]{1,64}")
                ? code.toLowerCase() : "unknown";
    }
}
