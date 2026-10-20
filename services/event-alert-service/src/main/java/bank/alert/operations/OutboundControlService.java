package bank.alert.operations;

import bank.alert.config.OperationalProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Quotas, the monthly cost stop and operator disables for outbound search and
 * model calls. The counts and disabled state live in an {@link OutboundControlStore}
 * so the API and every run-once worker share them (ADR-0031 decision 5).
 */
@Service
public class OutboundControlService {
    private final OperationalProperties properties;
    private final OperationalControlAuditSink auditSink;
    private final OperationalMetrics metrics;
    private final Clock clock;
    private final OutboundControlStore store;

    /** Single-process controls, for tests; the service uses the shared database store. */
    public OutboundControlService(OperationalProperties properties,
            OperationalControlAuditSink auditSink, OperationalMetrics metrics, Clock clock) {
        this(properties, auditSink, metrics, clock, new InMemoryOutboundControlStore());
    }

    @Autowired
    public OutboundControlService(OperationalProperties properties,
            OperationalControlAuditSink auditSink, OperationalMetrics metrics, Clock clock,
            OutboundControlStore store) {
        this.properties = properties;
        this.auditSink = auditSink;
        this.metrics = metrics;
        this.clock = clock;
        this.store = store;
        metrics.usage(0, 0, BigDecimal.ZERO);
    }

    public synchronized ReservationResult reserveSearches(int requested) {
        if (requested <= 0 || requested > properties.maxSearchesPerRun()) {
            return denied(OutboundCapability.INGESTION, "RUN_SEARCH_LIMIT", remainingSearches());
        }
        Optional<ControlReason> disabled = disabledReason(OutboundCapability.INGESTION);
        if (disabled.isPresent()) {
            return denied(OutboundCapability.INGESTION, disabled.get().name(), remainingSearches());
        }
        if (!store.reserveSearches(currentMonth(), requested, properties.monthlySearchQuota())) {
            disableInternal(OutboundCapability.INGESTION, ControlReason.SEARCH_QUOTA_REACHED);
            return denied(OutboundCapability.INGESTION, "SEARCH_QUOTA_REACHED", remainingSearches());
        }
        updateUsageMetrics();
        metrics.reservation(OutboundCapability.INGESTION, "ALLOWED");
        return ReservationResult.allowed(remainingSearches());
    }

    public synchronized ReservationResult reserveModelTokens(int requested) {
        if (requested <= 0 || requested > properties.maxModelTokensPerInvocation()) {
            return denied(OutboundCapability.MODEL, "INVOCATION_TOKEN_LIMIT", remainingModelTokens());
        }
        Optional<ControlReason> disabled = disabledReason(OutboundCapability.MODEL);
        if (disabled.isPresent()) {
            return denied(OutboundCapability.MODEL, disabled.get().name(), remainingModelTokens());
        }
        if (!store.reserveModelTokens(currentMonth(), requested, properties.monthlyModelTokenQuota())) {
            disableInternal(OutboundCapability.MODEL, ControlReason.MODEL_TOKEN_QUOTA_REACHED);
            return denied(OutboundCapability.MODEL, "MODEL_TOKEN_QUOTA_REACHED", remainingModelTokens());
        }
        updateUsageMetrics();
        metrics.reservation(OutboundCapability.MODEL, "ALLOWED");
        return ReservationResult.allowed(remainingModelTokens());
    }

    public synchronized void recordEstimatedCost(BigDecimal incrementalCostUsd) {
        if (incrementalCostUsd == null || incrementalCostUsd.signum() < 0) {
            throw new IllegalArgumentException("incremental cost must be non-negative");
        }
        BigDecimal total = store.addEstimatedCost(currentMonth(), incrementalCostUsd);
        updateUsageMetrics();
        if (total.compareTo(properties.monthlyBudgetUsd()) >= 0) {
            disableInternal(OutboundCapability.INGESTION, ControlReason.MONTHLY_BUDGET_REACHED);
            disableInternal(OutboundCapability.MODEL, ControlReason.MONTHLY_BUDGET_REACHED);
        }
    }

    public synchronized void disable(OutboundCapability capability) {
        disableInternal(capability, ControlReason.OPERATOR_DISABLED);
    }

    public synchronized void reenable(OutboundCapability capability, String actorSubject,
            String correlationId) {
        requireText(actorSubject, "actorSubject");
        requireText(correlationId, "correlationId");
        if (!configuredEnabled(capability)) {
            throw new IllegalStateException(capability + " is disabled by configuration");
        }
        Optional<ControlReason> previous = store.disabledReason(capability);
        if (previous.orElse(null) == ControlReason.MONTHLY_BUDGET_REACHED
                && usage().estimatedCostUsd().compareTo(properties.monthlyBudgetUsd()) >= 0) {
            throw new IllegalStateException("monthly budget remains exhausted");
        }
        ControlAuditEvent event = new ControlAuditEvent(capability, "REENABLED",
                actorSubject, correlationId, previous.orElse(null), Instant.now(clock));
        auditSink.record(event);
        store.clearDisabled(capability);
        metrics.reenabled(capability);
    }

    public synchronized ControlStatus status(OutboundCapability capability) {
        ControlReason reason = disabledReason(capability).orElse(null);
        return new ControlStatus(capability, reason == null, reason);
    }

    public synchronized OperationalUsageSnapshot usage() {
        return store.usage(currentMonth());
    }

    private ReservationResult denied(OutboundCapability capability, String code, long remaining) {
        metrics.reservation(capability, code);
        return ReservationResult.denied(code, remaining);
    }

    private void disableInternal(OutboundCapability capability, ControlReason reason) {
        Optional<ControlReason> previous = store.disable(capability, reason);
        if (previous.orElse(null) != reason) metrics.disabled(capability, reason);
    }

    /** Configuration always wins; otherwise the shared stored reason applies. */
    private Optional<ControlReason> disabledReason(OutboundCapability capability) {
        if (!configuredEnabled(capability)) return Optional.of(ControlReason.CONFIG_DISABLED);
        return store.disabledReason(capability);
    }

    private boolean configuredEnabled(OutboundCapability capability) {
        return capability == OutboundCapability.INGESTION
                ? properties.ingestionEnabled() : properties.modelEnabled();
    }

    private long remainingSearches() {
        return Math.max(0, properties.monthlySearchQuota() - usage().searchQueries());
    }

    private long remainingModelTokens() {
        return Math.max(0, properties.monthlyModelTokenQuota() - usage().modelTokens());
    }

    private YearMonth currentMonth() {
        return YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
    }

    private void updateUsageMetrics() {
        OperationalUsageSnapshot usage = usage();
        metrics.usage(usage.searchQueries(), usage.modelTokens(), usage.estimatedCostUsd());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must contain 1 to 128 characters");
        }
    }
}
