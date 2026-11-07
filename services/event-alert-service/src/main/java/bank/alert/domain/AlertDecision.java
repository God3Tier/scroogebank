package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "alert_decision", uniqueConstraints = @UniqueConstraint(
        name = "uk_alert_decision_actor_idempotency",
        columnNames = {"actor_subject", "idempotency_key"}))
public class AlertDecision {

    @Id
    private UUID id;

    @Column(name = "alert_id", nullable = false)
    private UUID alertId;

    @Column(name = "actor_subject", nullable = false, length = 128)
    private String actorSubject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertDecisionType decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 20)
    private AlertStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private AlertStatus newStatus;

    @Column(length = 500)
    private String note;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "alert_version", nullable = false)
    private long alertVersion;

    protected AlertDecision() {
    }

    public AlertDecision(UUID id, UUID alertId, String actorSubject, AlertDecisionType decision,
                         AlertStatus previousStatus, AlertStatus newStatus, String note,
                         String idempotencyKey, Instant decidedAt, long alertVersion) {
        this.id = Objects.requireNonNull(id);
        this.alertId = Objects.requireNonNull(alertId);
        this.actorSubject = Objects.requireNonNull(actorSubject);
        this.decision = Objects.requireNonNull(decision);
        this.previousStatus = Objects.requireNonNull(previousStatus);
        this.newStatus = Objects.requireNonNull(newStatus);
        this.note = note;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.decidedAt = Objects.requireNonNull(decidedAt);
        if (alertVersion < 1) {
            throw new IllegalArgumentException("alertVersion must be at least 1");
        }
        this.alertVersion = alertVersion;
    }

    public UUID getId() { return id; }
    public UUID getAlertId() { return alertId; }
    public String getActorSubject() { return actorSubject; }
    public AlertDecisionType getDecision() { return decision; }
    public AlertStatus getPreviousStatus() { return previousStatus; }
    public AlertStatus getNewStatus() { return newStatus; }
    public String getNote() { return note; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getDecidedAt() { return decidedAt; }
    public long getAlertVersion() { return alertVersion; }
}
