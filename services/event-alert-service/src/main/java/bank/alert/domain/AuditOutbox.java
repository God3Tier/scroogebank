package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "audit_outbox")
public class AuditOutbox {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    protected AuditOutbox() {
    }

    public AuditOutbox(UUID eventId, String eventType, int eventVersion, String payload, Instant createdAt) {
        this.eventId = Objects.requireNonNull(eventId);
        this.eventType = Objects.requireNonNull(eventType);
        this.eventVersion = eventVersion;
        this.payload = Objects.requireNonNull(payload);
        this.status = "PENDING";
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public int getEventVersion() { return eventVersion; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getLeaseUntil() { return leaseUntil; }

    public void leaseUntil(Instant until) {
        status = "LEASED";
        leaseUntil = Objects.requireNonNull(until);
        attemptCount++;
    }

    public void delivered(Instant at) {
        status = "DELIVERED";
        acknowledgedAt = Objects.requireNonNull(at);
        leaseUntil = null;
    }

    public void failed() {
        status = "FAILED";
        leaseUntil = null;
    }
}
