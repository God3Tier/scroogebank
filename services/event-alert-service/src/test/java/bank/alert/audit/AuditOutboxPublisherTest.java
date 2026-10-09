package bank.alert.audit;

import bank.alert.domain.AuditOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void acknowledgesOnlyAfterGatewaySuccess() {
        AuditOutboxLeaseService leases = mock(AuditOutboxLeaseService.class);
        AuditDeliveryGateway gateway = mock(AuditDeliveryGateway.class);
        AuditOutbox event = event();
        when(leases.claim(NOW, 10, Duration.ofSeconds(30), 5)).thenReturn(List.of(event));
        AuditOutboxPublisher publisher = publisher(leases, gateway);

        publisher.publishAvailable();

        verify(gateway).publish(eq(event.getEventId().toString()), any(AuditorEnvelope.class));
        verify(leases).delivered(event.getEventId(), NOW);
    }

    @Test
    void returnsFailedDeliveryToRetryableState() {
        AuditOutboxLeaseService leases = mock(AuditOutboxLeaseService.class);
        AuditDeliveryGateway gateway = mock(AuditDeliveryGateway.class);
        AuditOutbox event = event();
        when(leases.claim(NOW, 10, Duration.ofSeconds(30), 5)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("auditor unavailable"))
                .when(gateway).publish(eq(event.getEventId().toString()), any(AuditorEnvelope.class));
        AuditOutboxPublisher publisher = publisher(leases, gateway);

        publisher.publishAvailable();

        verify(leases).failed(event.getEventId());
    }

    @Test
    void continuesPublishingTheBatchAfterOneDeliveryFails() {
        AuditOutboxLeaseService leases = mock(AuditOutboxLeaseService.class);
        AuditDeliveryGateway gateway = mock(AuditDeliveryGateway.class);
        AuditOutbox failed = event();
        AuditOutbox delivered = new AuditOutbox(
                UUID.fromString("70000000-0000-4000-8000-000000000002"),
                "alert.generated", 1, "{\"alertId\":\"second\"}", NOW);
        when(leases.claim(NOW, 10, Duration.ofSeconds(30), 5))
                .thenReturn(List.of(failed, delivered));
        doThrow(new IllegalStateException("auditor unavailable"))
                .when(gateway).publish(eq(failed.getEventId().toString()), any(AuditorEnvelope.class));
        AuditOutboxPublisher publisher = publisher(leases, gateway);

        publisher.publishAvailable();

        verify(leases).failed(failed.getEventId());
        verify(gateway).publish(eq(delivered.getEventId().toString()), any(AuditorEnvelope.class));
        verify(leases).delivered(delivered.getEventId(), NOW);
    }

    private static AuditOutbox event() {
        return new AuditOutbox(UUID.fromString("70000000-0000-4000-8000-000000000001"),
                "alert.generated", 1, "{\"alertId\":\"synthetic\"}", NOW);
    }

    private static AuditOutboxPublisher publisher(AuditOutboxLeaseService leases,
            AuditDeliveryGateway gateway) {
        return new AuditOutboxPublisher(leases, gateway,
                new AuditorEnvelopeFactory(new ObjectMapper().findAndRegisterModules()),
                Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofSeconds(30), 5);
    }
}
