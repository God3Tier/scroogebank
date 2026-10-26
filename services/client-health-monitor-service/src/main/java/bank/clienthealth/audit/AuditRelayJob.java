package bank.clienthealth.audit;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded relay loop; individual event failures are released by the relay. */
public final class AuditRelayJob {
    private static final Logger LOG = LoggerFactory.getLogger(AuditRelayJob.class);
    private final AuditOutboxRelay relay;
    private final Clock clock;
    private final int batchSize;
    private final Duration leaseDuration;

    public AuditRelayJob(
            AuditOutboxRelay relay,
            Clock clock,
            int batchSize,
            Duration leaseDuration
    ) {
        if (batchSize < 1 || batchSize > 100
                || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("invalid audit relay configuration");
        }
        this.relay = java.util.Objects.requireNonNull(relay);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
    }

    @Scheduled(fixedDelayString = "${client-health.audit.relay-delay-ms:2000}")
    public void relay() {
        try {
            this.relay.relay(clock.instant(), batchSize, leaseDuration);
        } catch (RuntimeException failure) {
            LOG.warn("Client Health audit relay batch failed");
        }
    }
}
