package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "alert", uniqueConstraints = @UniqueConstraint(
        name = "uk_alert_client_event_instrument",
        columnNames = {"client_ref", "public_event_id", "instrument_id"}))
public class Alert {

    @Id
    private UUID id;

    @Column(name = "client_ref", nullable = false, length = 64)
    private String clientRef;

    @Column(name = "assigned_agent_subject", nullable = false, length = 128)
    private String assignedAgentSubject;

    @Column(name = "public_event_id", nullable = false)
    private UUID publicEventId;

    @Column(name = "instrument_id", nullable = false, length = 32)
    private String instrumentId;

    @Column(name = "holding_snapshot_id", nullable = false)
    private UUID holdingSnapshotId;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "rule_version", nullable = false, length = 50)
    private String ruleVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Alert() {
    }

    public Alert(UUID id, String clientRef, String assignedAgentSubject, UUID publicEventId,
                 String instrumentId, UUID holdingSnapshotId, BigDecimal score,
                 String ruleVersion, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.clientRef = requireText(clientRef, "clientRef");
        this.assignedAgentSubject = requireText(assignedAgentSubject, "assignedAgentSubject");
        this.publicEventId = Objects.requireNonNull(publicEventId);
        this.instrumentId = requireText(instrumentId, "instrumentId");
        this.holdingSnapshotId = Objects.requireNonNull(holdingSnapshotId);
        this.score = Objects.requireNonNull(score);
        this.ruleVersion = requireText(ruleVersion, "ruleVersion");
        this.status = AlertStatus.NEW;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public void transitionTo(AlertStatus nextStatus) {
        this.status = Objects.requireNonNull(nextStatus);
    }

    public UUID getId() { return id; }
    public String getClientRef() { return clientRef; }
    public String getAssignedAgentSubject() { return assignedAgentSubject; }
    public UUID getPublicEventId() { return publicEventId; }
    public String getInstrumentId() { return instrumentId; }
    public UUID getHoldingSnapshotId() { return holdingSnapshotId; }
    public BigDecimal getScore() { return score; }
    public String getRuleVersion() { return ruleVersion; }
    public AlertStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
