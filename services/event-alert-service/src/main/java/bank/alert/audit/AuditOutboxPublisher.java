package bank.alert.audit;

import bank.alert.domain.AuditOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "alert.audit.publisher", name = "enabled", havingValue = "true")
public class AuditOutboxPublisher {

    private final AuditOutboxLeaseService leases;
    private final AuditDeliveryGateway gateway;
    private final AuditorEnvelopeFactory envelopes;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration leaseDuration;

    public AuditOutboxPublisher(AuditOutboxLeaseService leases, AuditDeliveryGateway gateway,
            AuditorEnvelopeFactory envelopes, Clock clock,
            @Value("${alert.audit.publisher.batch-size:50}") int batchSize,
            @Value("${alert.audit.publisher.lease-duration:30s}") Duration leaseDuration,
            @Value("${alert.audit.publisher.max-attempts:10}") int maxAttempts) {
        this.leases = leases;
        this.gateway = gateway;
        this.envelopes = envelopes;
        this.clock = clock;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${alert.audit.publisher.interval:5s}")
    public void publishAvailable() {
        List<AuditOutbox> claimed = leases.claim(
                clock.instant(), batchSize, leaseDuration, maxAttempts);
        for (AuditOutbox event : claimed) {
            try {
                gateway.publish(event.getEventId().toString(), envelopes.create(event));
                leases.delivered(event.getEventId(), clock.instant());
            } catch (RuntimeException exception) {
                leases.failed(event.getEventId());
            }
        }
    }
}
