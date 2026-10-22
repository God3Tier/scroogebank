package bank.engagement.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One queued message to an agent (ADR-0051 decision 4). */
@Entity
@Table(name = "mail_queue")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MailQueueEntry {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MailKind kind;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "owner_agent_sub", nullable = false, length = 64)
    private String ownerAgentSub;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "offset_minutes", nullable = false)
    private int offsetMinutes;

    @Column(name = "ics_sequence", nullable = false)
    private int icsSequence;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MailStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "leased_until")
    private Instant leasedUntil;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "ses_message_id", length = 200)
    private String sesMessageId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    MailQueueEntry(MailKind kind, UUID eventId, String ownerAgentSub, int eventVersion, int offsetMinutes,
            int icsSequence, Instant dueAt, MailStatus status, Instant now) {
        this.id = UUID.randomUUID();
        this.kind = Objects.requireNonNull(kind);
        this.eventId = Objects.requireNonNull(eventId);
        this.ownerAgentSub = Objects.requireNonNull(ownerAgentSub);
        this.eventVersion = eventVersion;
        this.offsetMinutes = offsetMinutes;
        this.icsSequence = icsSequence;
        this.dueAt = Objects.requireNonNull(dueAt);
        this.status = Objects.requireNonNull(status);
        this.createdAt = now;
        this.updatedAt = now;
    }

    QueuedMail toQueuedMail() {
        return new QueuedMail(id, kind, eventId, ownerAgentSub, offsetMinutes, icsSequence, dueAt);
    }

    void lease(Instant until) {
        leasedUntil = until;
    }

    void sent(String messageId, Instant now) {
        status = MailStatus.SENT;
        sesMessageId = messageId;
        sentAt = now;
        attempts++;
        finish(null, now);
    }

    void close(MailStatus finalStatus, String errorCode, Instant now) {
        status = finalStatus;
        finish(errorCode, now);
    }

    /** One more failed send. Returns false when no retry is left and the row has failed. */
    boolean failedAttempt(String errorCode, List<Duration> retryDelays, Instant now) {
        attempts++;
        if (attempts > retryDelays.size()) {
            close(MailStatus.FAILED, errorCode, now);
            return false;
        }
        nextAttemptAt = now.plus(retryDelays.get(attempts - 1));
        finish(errorCode, now);
        return true;
    }

    private void finish(String errorCode, Instant now) {
        lastErrorCode = errorCode;
        leasedUntil = null;
        updatedAt = now;
    }

    UUID getId() { return id; }
    MailKind getKind() { return kind; }
    UUID getEventId() { return eventId; }
    int getEventVersion() { return eventVersion; }
    int getOffsetMinutes() { return offsetMinutes; }
    int getIcsSequence() { return icsSequence; }
    Instant getDueAt() { return dueAt; }
    MailStatus getStatus() { return status; }
    int getAttempts() { return attempts; }
    Instant getNextAttemptAt() { return nextAttemptAt; }
    Instant getLeasedUntil() { return leasedUntil; }
    String getLastErrorCode() { return lastErrorCode; }
    String getSesMessageId() { return sesMessageId; }
    Instant getCreatedAt() { return createdAt; }
}
