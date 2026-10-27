package bank.clienthealth.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Persistence boundary; enqueue must share the caller's business transaction. */
public interface AuditOutboxRepository {
    void enqueue(RelationshipHealthAuditEvent event);

    List<LeasedAuditEvent> leaseBatch(
            Instant now,
            int limit,
            Duration leaseDuration
    );

    void markDelivered(String eventId, String leaseToken);

    void release(
            String eventId,
            String leaseToken,
            Instant nextAttemptAt,
            FailureCategory category
    );

    default Backlog backlog(Instant now) { return new Backlog(0, Duration.ZERO); }

    record LeasedAuditEvent(
            RelationshipHealthAuditEvent event,
            String leaseToken,
            int attemptCount,
            boolean recovered
    ) {
        public LeasedAuditEvent(RelationshipHealthAuditEvent event, String leaseToken, int attemptCount) {
            this(event, leaseToken, attemptCount, false);
        }
        public LeasedAuditEvent {
            event = Objects.requireNonNull(event);
            leaseToken = Objects.requireNonNull(leaseToken);
            if (attemptCount < 1) {
                throw new IllegalArgumentException("attemptCount must be positive");
            }
        }
    }

    record Backlog(long pendingCount, Duration oldestAge) {
        public Backlog {
            if (pendingCount < 0 || oldestAge == null || oldestAge.isNegative()) {
                throw new IllegalArgumentException("invalid audit backlog");
            }
        }
    }

    enum FailureCategory {
        DELIVERY_UNAVAILABLE,
        DELIVERY_REJECTED,
        UNKNOWN
    }
}
