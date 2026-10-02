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
@Table(name = "alert_feedback", uniqueConstraints = @UniqueConstraint(
        name = "uk_alert_feedback_actor_idempotency",
        columnNames = {"actor_subject", "idempotency_key"}))
public class AlertFeedback {

    @Id
    private UUID id;

    @Column(name = "alert_id", nullable = false)
    private UUID alertId;

    @Column(name = "actor_subject", nullable = false, length = 128)
    private String actorSubject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FeedbackRating rating;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 32)
    private FeedbackReasonCode reasonCode;

    @Column(length = 500)
    private String comment;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "alert_version", nullable = false)
    private long alertVersion;

    protected AlertFeedback() {
    }

    public AlertFeedback(
            UUID id,
            UUID alertId,
            String actorSubject,
            FeedbackRating rating,
            FeedbackReasonCode reasonCode,
            String comment,
            String idempotencyKey,
            Instant recordedAt,
            long alertVersion) {
        this.id = Objects.requireNonNull(id);
        this.alertId = Objects.requireNonNull(alertId);
        this.actorSubject = requireText(actorSubject, "actorSubject");
        this.rating = Objects.requireNonNull(rating);
        this.reasonCode = reasonCode;
        this.comment = comment;
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.recordedAt = Objects.requireNonNull(recordedAt);
        if (alertVersion < 0) {
            throw new IllegalArgumentException("alertVersion must not be negative");
        }
        this.alertVersion = alertVersion;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public UUID getId() { return id; }
    public UUID getAlertId() { return alertId; }
    public String getActorSubject() { return actorSubject; }
    public FeedbackRating getRating() { return rating; }
    public FeedbackReasonCode getReasonCode() { return reasonCode; }
    public String getComment() { return comment; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getRecordedAt() { return recordedAt; }
    public long getAlertVersion() { return alertVersion; }
}
