package bank.clienthealth.audit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;

/** Pull-based outbox gauges that query aggregate columns only. */
public final class AuditBacklogMetrics {
    public AuditBacklogMetrics(AuditOutboxRepository repository, Clock clock, MeterRegistry registry) {
        Gauge.builder("client_health_audit_backlog", repository,
                        value -> pending(value, clock))
                .strongReference(true).register(registry);
        Gauge.builder("client_health_audit_oldest_age_seconds", repository,
                        value -> age(value, clock))
                .strongReference(true).register(registry);
    }

    private static double pending(AuditOutboxRepository repository, Clock clock) {
        try {
            return repository.backlog(clock.instant()).pendingCount();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }

    private static double age(AuditOutboxRepository repository, Clock clock) {
        try {
            return repository.backlog(clock.instant()).oldestAge().toMillis() / 1000.0;
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }
}
