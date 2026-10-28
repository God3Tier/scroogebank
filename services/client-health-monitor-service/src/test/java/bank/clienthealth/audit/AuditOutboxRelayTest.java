package bank.clienthealth.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bank.clienthealth.audit.AuditDeliveryGateway.DeliveryException;
import bank.clienthealth.audit.AuditOutboxRepository.FailureCategory;
import bank.clienthealth.audit.AuditOutboxRepository.LeasedAuditEvent;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.Action;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import bank.clienthealth.metrics.MicrometerClientHealthMetrics;

class AuditOutboxRelayTest {
    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Test
    void enqueueIsIdempotentAndRelayContinuesAfterOneDeliveryFailure() {
        var repository = new InMemoryOutbox();
        var first = event("run-1", Action.ANALYSIS_RUN_STARTED);
        var second = event("run-2", Action.ANALYSIS_RUN_COMPLETED);
        repository.enqueue(first);
        repository.enqueue(first);
        repository.enqueue(second);
        var failFirst = new AtomicBoolean(true);
        List<String> sent = new ArrayList<>();
        AuditDeliveryGateway gateway = event -> {
            if (event.eventId().equals(first.eventId()) && failFirst.getAndSet(false)) {
                throw new DeliveryException(
                        FailureCategory.DELIVERY_UNAVAILABLE, "synthetic outage");
            }
            sent.add(event.eventId());
        };
        var registry = new SimpleMeterRegistry();
        var relay = new AuditOutboxRelay(
                repository, gateway, new MicrometerClientHealthMetrics(registry));

        var initial = relay.relay(NOW, 10, Duration.ofSeconds(30));

        assertEquals(new AuditOutboxRelay.RelayResult(2, 1, 1), initial);
        assertEquals(List.of(second.eventId()), sent);
        assertEquals(FailureCategory.DELIVERY_UNAVAILABLE, repository.failure(first.eventId()));
        assertEquals(NOW.plusSeconds(1), repository.nextAttempt(first.eventId()));
        assertEquals(new AuditOutboxRelay.RelayResult(0, 0, 0),
                relay.relay(NOW, 10, Duration.ofSeconds(30)));

        var retried = relay.relay(NOW.plusSeconds(1), 10, Duration.ofSeconds(30));

        assertEquals(new AuditOutboxRelay.RelayResult(1, 1, 0), retried);
        assertEquals(List.of(second.eventId(), first.eventId()), sent);
        assertEquals(0, repository.size());
        assertEquals(2, registry.get("client_health_audit_events_total")
                .tag("outcome", "delivered").counter().count());
        assertEquals(1, registry.get("client_health_audit_events_total")
                .tags("outcome", "released", "failure", "unavailable").counter().count());
    }

    @Test
    void unknownGatewayFailureIsReleasedWithBoundedBackoff() {
        var repository = new InMemoryOutbox();
        var event = event("run-1", Action.ANALYSIS_RUN_FAILED);
        repository.enqueue(event);
        AuditOutboxRelay relay = new AuditOutboxRelay(
                repository, ignored -> {
                    throw new IllegalStateException("unexpected");
                });

        relay.relay(NOW, 10, Duration.ofSeconds(30));

        assertEquals(FailureCategory.UNKNOWN, repository.failure(event.eventId()));
        assertEquals(NOW.plusSeconds(1), repository.nextAttempt(event.eventId()));
    }

    private static RelationshipHealthAuditEvent event(String runId, Action action) {
        return RelationshipHealthAuditEventFactory.analysis(
                runId, action, NOW, "correlation-" + runId,
                action == Action.ANALYSIS_RUN_FAILED ? "UNEXPECTED" : null);
    }

    private static final class InMemoryOutbox implements AuditOutboxRepository {
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private int leaseSequence;

        @Override
        public void enqueue(RelationshipHealthAuditEvent event) {
            entries.putIfAbsent(event.eventId(), new Entry(event));
        }

        @Override
        public List<LeasedAuditEvent> leaseBatch(
                Instant now, int limit, Duration leaseDuration
        ) {
            List<LeasedAuditEvent> leased = new ArrayList<>();
            for (Entry entry : entries.values()) {
                if (leased.size() == limit) break;
                if (entry.leaseToken != null || entry.nextAttempt.isAfter(now)) continue;
                entry.attempts++;
                entry.leaseToken = "lease-" + ++leaseSequence;
                leased.add(new LeasedAuditEvent(
                        entry.event, entry.leaseToken, entry.attempts));
            }
            return leased;
        }

        @Override
        public void markDelivered(String eventId, String leaseToken) {
            requireLease(eventId, leaseToken);
            entries.remove(eventId);
        }

        @Override
        public void release(
                String eventId,
                String leaseToken,
                Instant nextAttemptAt,
                FailureCategory category
        ) {
            Entry entry = requireLease(eventId, leaseToken);
            entry.leaseToken = null;
            entry.nextAttempt = nextAttemptAt;
            entry.failure = category;
        }

        int size() {
            return entries.size();
        }

        Instant nextAttempt(String eventId) {
            return entries.get(eventId).nextAttempt;
        }

        FailureCategory failure(String eventId) {
            return entries.get(eventId).failure;
        }

        private Entry requireLease(String eventId, String leaseToken) {
            Entry entry = entries.get(eventId);
            if (entry == null || !leaseToken.equals(entry.leaseToken)) {
                throw new IllegalStateException("lease does not own event");
            }
            return entry;
        }

        private static final class Entry {
            final RelationshipHealthAuditEvent event;
            int attempts;
            String leaseToken;
            Instant nextAttempt = Instant.MIN;
            FailureCategory failure;

            Entry(RelationshipHealthAuditEvent event) {
                this.event = event;
            }
        }
    }
}
