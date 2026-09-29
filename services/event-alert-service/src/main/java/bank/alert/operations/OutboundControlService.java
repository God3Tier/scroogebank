package bank.alert.operations;

import bank.alert.config.OperationalProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;

@Service
public class OutboundControlService {
    private final OperationalProperties properties;
    private final OperationalControlAuditSink auditSink;
    private final OperationalMetrics metrics;
    private final Clock clock;
    private final Map<OutboundCapability, ControlReason> disabledReasons =
            new EnumMap<>(OutboundCapability.class);
    private YearMonth usageMonth;
    private int searchQueries;
    private long modelTokens;
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    public OutboundControlService(OperationalProperties properties,
            OperationalControlAuditSink auditSink, OperationalMetrics metrics, Clock clock) {
        this.properties = properties;
        this.auditSink = auditSink;
        this.metrics = metrics;
        this.clock = clock;
        this.usageMonth = currentMonth();
        if (!properties.ingestionEnabled()) {
            disabledReasons.put(OutboundCapability.INGESTION, ControlReason.CONFIG_DISABLED);
        }
        if (!properties.modelEnabled()) {
            disabledReasons.put(OutboundCapability.MODEL, ControlReason.CONFIG_DISABLED);
        }
        metrics.usage(0, 0, BigDecimal.ZERO);
    }

    public synchronized ReservationResult reserveSearches(int requested) {
        rollMonth();
        if (requested <= 0 || requested > properties.maxSearchesPerRun()) {
            return denied(OutboundCapability.INGESTION, "RUN_SEARCH_LIMIT", remainingSearches());
        }
        if (disabledReasons.containsKey(OutboundCapability.INGESTION)) {
            return denied(OutboundCapability.INGESTION, disabledCode(OutboundCapability.INGESTION),
                    remainingSearches());
        }
        if ((long) searchQueries + requested > properties.monthlySearchQuota()) {
            disableInternal(OutboundCapability.INGESTION, ControlReason.SEARCH_QUOTA_REACHED);
            return denied(OutboundCapability.INGESTION, "SEARCH_QUOTA_REACHED", remainingSearches());
        }
        searchQueries += requested;
        updateUsageMetrics();
        metrics.reservation(OutboundCapability.INGESTION, "ALLOWED");
        return ReservationResult.allowed(remainingSearches());
    }

    public synchronized ReservationResult reserveModelTokens(int requested) {
        rollMonth();
        if (requested <= 0 || requested > properties.maxModelTokensPerInvocation()) {
            return denied(OutboundCapability.MODEL, "INVOCATION_TOKEN_LIMIT", remainingModelTokens());
        }
        if (disabledReasons.containsKey(OutboundCapability.MODEL)) {
            return denied(OutboundCapability.MODEL, disabledCode(OutboundCapability.MODEL),
                    remainingModelTokens());
        }
        if (modelTokens + requested > properties.monthlyModelTokenQuota()) {
            disableInternal(OutboundCapability.MODEL, ControlReason.MODEL_TOKEN_QUOTA_REACHED);
            return denied(OutboundCapability.MODEL, "MODEL_TOKEN_QUOTA_REACHED", remainingModelTokens());
        }
        modelTokens += requested;
        updateUsageMetrics();
        metrics.reservation(OutboundCapability.MODEL, "ALLOWED");
        return ReservationResult.allowed(remainingModelTokens());
    }

    public synchronized void recordEstimatedCost(BigDecimal incrementalCostUsd) {
        rollMonth();
        if (incrementalCostUsd == null || incrementalCostUsd.signum() < 0) {
            throw new IllegalArgumentException("incremental cost must be non-negative");
        }
        estimatedCostUsd = estimatedCostUsd.add(incrementalCostUsd);
        updateUsageMetrics();
        if (estimatedCostUsd.compareTo(properties.monthlyBudgetUsd()) >= 0) {
            disableInternal(OutboundCapability.INGESTION, ControlReason.MONTHLY_BUDGET_REACHED);
            disableInternal(OutboundCapability.MODEL, ControlReason.MONTHLY_BUDGET_REACHED);
        }
    }

    public synchronized void disable(OutboundCapability capability) {
        disableInternal(capability, ControlReason.OPERATOR_DISABLED);
    }

    public synchronized void reenable(OutboundCapability capability, String actorSubject,
            String correlationId) {
        rollMonth();
        requireText(actorSubject, "actorSubject");
        requireText(correlationId, "correlationId");
        if (!configuredEnabled(capability)) {
            throw new IllegalStateException(capability + " is disabled by configuration");
        }
        if (disabledReasons.get(capability) == ControlReason.MONTHLY_BUDGET_REACHED
                && estimatedCostUsd.compareTo(properties.monthlyBudgetUsd()) >= 0) {
            throw new IllegalStateException("monthly budget remains exhausted");
        }
        ControlAuditEvent event = new ControlAuditEvent(capability, "REENABLED",
                actorSubject, correlationId, Instant.now(clock));
        auditSink.record(event);
        disabledReasons.remove(capability);
        metrics.reenabled(capability);
    }

    public synchronized ControlStatus status(OutboundCapability capability) {
        ControlReason reason = disabledReasons.get(capability);
        return new ControlStatus(capability, reason == null, reason);
    }

    public synchronized OperationalUsageSnapshot usage() {
        rollMonth();
        return new OperationalUsageSnapshot(usageMonth, searchQueries, modelTokens, estimatedCostUsd);
    }

    private ReservationResult denied(OutboundCapability capability, String code, long remaining) {
        metrics.reservation(capability, code);
        return ReservationResult.denied(code, remaining);
    }

    private void disableInternal(OutboundCapability capability, ControlReason reason) {
        ControlReason previous = disabledReasons.put(capability, reason);
        if (previous != reason) metrics.disabled(capability, reason);
    }

    private String disabledCode(OutboundCapability capability) {
        return disabledReasons.get(capability).name();
    }

    private boolean configuredEnabled(OutboundCapability capability) {
        return capability == OutboundCapability.INGESTION
                ? properties.ingestionEnabled() : properties.modelEnabled();
    }

    private long remainingSearches() {
        return Math.max(0, properties.monthlySearchQuota() - searchQueries);
    }

    private long remainingModelTokens() {
        return Math.max(0, properties.monthlyModelTokenQuota() - modelTokens);
    }

    private void rollMonth() {
        YearMonth now = currentMonth();
        if (!now.equals(usageMonth)) {
            usageMonth = now;
            searchQueries = 0;
            modelTokens = 0;
            estimatedCostUsd = BigDecimal.ZERO;
            updateUsageMetrics();
        }
    }

    private YearMonth currentMonth() {
        return YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
    }

    private void updateUsageMetrics() {
        metrics.usage(searchQueries, modelTokens, estimatedCostUsd);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must contain 1 to 128 characters");
        }
    }
}
