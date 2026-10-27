package bank.clienthealth.audit;

import bank.clienthealth.audit.AuditDeliveryGateway.DeliveryException;
import bank.clienthealth.audit.AuditOutboxRepository.FailureCategory;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Leases and delivers a bounded batch without losing later events on failure. */
public final class AuditOutboxRelay {
    private static final long MAX_BACKOFF_SECONDS = 300;
    private final AuditOutboxRepository repository;
    private final AuditDeliveryGateway gateway;

    public AuditOutboxRelay(
            AuditOutboxRepository repository,
            AuditDeliveryGateway gateway
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.gateway = Objects.requireNonNull(gateway);
    }

    public RelayResult relay(
            Instant now,
            int limit,
            Duration leaseDuration
    ) {
        Objects.requireNonNull(now);
        Objects.requireNonNull(leaseDuration);
        if (limit < 1 || limit > 100 || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("invalid outbox relay bounds");
        }
        int delivered = 0;
        int released = 0;
        var leased = repository.leaseBatch(now, limit, leaseDuration);
        for (var entry : leased) {
            try {
                gateway.send(entry.event());
                repository.markDelivered(entry.event().eventId(), entry.leaseToken());
                delivered++;
            } catch (DeliveryException failure) {
                release(now, entry, failure.category());
                released++;
            } catch (RuntimeException failure) {
                release(now, entry, FailureCategory.UNKNOWN);
                released++;
            }
        }
        return new RelayResult(leased.size(), delivered, released);
    }

    private void release(
            Instant now,
            AuditOutboxRepository.LeasedAuditEvent entry,
            FailureCategory category
    ) {
        int exponent = Math.min(entry.attemptCount() - 1, 8);
        long delay = Math.min(1L << exponent, MAX_BACKOFF_SECONDS);
        repository.release(
                entry.event().eventId(),
                entry.leaseToken(),
                now.plusSeconds(delay),
                category);
    }

    public record RelayResult(int leased, int delivered, int released) {
        public RelayResult {
            if (leased < 0 || delivered < 0 || released < 0
                    || delivered + released != leased) {
                throw new IllegalArgumentException("invalid relay counts");
            }
        }
    }
}
