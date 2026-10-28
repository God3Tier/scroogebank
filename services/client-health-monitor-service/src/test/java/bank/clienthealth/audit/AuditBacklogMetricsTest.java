package bank.clienthealth.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuditBacklogMetricsTest {
    @Test
    void gaugesReadAggregateBacklogOnly() {
        Instant now = Instant.parse("2026-10-27T04:00:00Z");
        AuditOutboxRepository repository = new AuditOutboxRepository() {
            public void enqueue(RelationshipHealthAuditEvent event) { }
            public java.util.List<LeasedAuditEvent> leaseBatch(Instant at, int limit, Duration lease) {
                return java.util.List.of();
            }
            public void markDelivered(String eventId, String leaseToken) { }
            public void release(String eventId, String token, Instant next, FailureCategory category) { }
            public Backlog backlog(Instant at) { return new Backlog(3, Duration.ofSeconds(45)); }
        };
        var registry = new SimpleMeterRegistry();
        new AuditBacklogMetrics(repository, Clock.fixed(now, ZoneOffset.UTC), registry);

        assertThat(registry.get("client_health_audit_backlog").gauge().value()).isEqualTo(3);
        assertThat(registry.get("client_health_audit_oldest_age_seconds").gauge().value()).isEqualTo(45);
    }
}
