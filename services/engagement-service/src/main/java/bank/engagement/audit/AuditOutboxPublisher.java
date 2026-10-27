package bank.engagement.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Relays outbox rows to the shared audit queue (ADR-0020), as event-alert-service does. */
@Component
@ConditionalOnProperty(prefix = "engagement.audit.publisher", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AuditOutboxPublisher {

    private final AuditOutboxLeaseService leases;
    private final AuditDeliveryGateway gateway;
    private final AuditorEnvelopeFactory envelopes;
    private final Clock clock;
    // lombok.config copies these @Value annotations onto the constructor parameters.
    @Value("${engagement.audit.publisher.batch-size:50}")
    private final int batchSize;
    @Value("${engagement.audit.publisher.lease-duration:30s}")
    private final Duration leaseDuration;
    @Value("${engagement.audit.publisher.max-attempts:10}")
    private final int maxAttempts;

    @Scheduled(fixedDelayString = "${engagement.audit.publisher.interval:5s}")
    public void publishAvailable() {
        List<AuditOutbox> claimed = leases.claim(clock.instant(), batchSize, leaseDuration, maxAttempts);
        for (AuditOutbox event : claimed) {
            try {
                gateway.publish(event.getEventId().toString(), envelopes.create(event));
                leases.delivered(event.getEventId(), clock.instant());
            } catch (RuntimeException exception) {
                log.warn("Audit event {} not delivered: {}", event.getEventId(), exception.getClass().getSimpleName());
                leases.failed(event.getEventId());
            }
        }
    }
}
