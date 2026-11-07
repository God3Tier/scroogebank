package bank.clienthealth.audit;

import bank.clienthealth.audit.AuditDeliveryGateway.DeliveryException;
import bank.clienthealth.audit.AuditOutboxRepository.FailureCategory;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import bank.clienthealth.metrics.ClientHealthMetrics;
import bank.clienthealth.metrics.ClientHealthMetrics.AuditFailure;
import bank.clienthealth.metrics.ClientHealthMetrics.AuditOutcome;

/** Leases and delivers a bounded batch without losing later events on failure. */
public final class AuditOutboxRelay {
    private static final long MAX_BACKOFF_SECONDS = 300;
    private final AuditOutboxRepository repository;
    private final AuditDeliveryGateway gateway;
    private final ClientHealthMetrics metrics;

    public AuditOutboxRelay(
            AuditOutboxRepository repository,
            AuditDeliveryGateway gateway
    ) {
        this(repository, gateway, ClientHealthMetrics.noOp());
    }

    public AuditOutboxRelay(AuditOutboxRepository repository, AuditDeliveryGateway gateway,
            ClientHealthMetrics metrics) {
        this.repository = Objects.requireNonNull(repository);
        this.gateway = Objects.requireNonNull(gateway);
        this.metrics = Objects.requireNonNull(metrics);
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
            if (entry.recovered()) metrics.audit(AuditOutcome.RECOVERED, AuditFailure.NONE);
            try {
                gateway.send(entry.event());
                repository.markDelivered(entry.event().eventId(), entry.leaseToken());
                delivered++;
                metrics.audit(AuditOutcome.DELIVERED, AuditFailure.NONE);
            } catch (DeliveryException failure) {
                release(now, entry, failure.category());
                released++;
                metrics.audit(AuditOutcome.RELEASED, failure(failure.category()));
            } catch (RuntimeException failure) {
                release(now, entry, FailureCategory.UNKNOWN);
                released++;
                metrics.audit(AuditOutcome.RELEASED, AuditFailure.UNKNOWN);
            }
        }
        return new RelayResult(leased.size(), delivered, released);
    }

    private static AuditFailure failure(FailureCategory category) {
        return switch (category) {
            case DELIVERY_UNAVAILABLE -> AuditFailure.UNAVAILABLE;
            case DELIVERY_REJECTED -> AuditFailure.REJECTED;
            case UNKNOWN -> AuditFailure.UNKNOWN;
        };
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
