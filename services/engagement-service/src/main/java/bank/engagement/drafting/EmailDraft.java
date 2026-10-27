package bank.engagement.drafting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A follow-up draft. Subject and body keep their placeholders; the client's
 * name and address are filled in only for a preview or the send, and never
 * stored (ADR-0054 decision 6, ADR-0053 decision 7).
 */
@Entity
@Table(name = "email_drafts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class EmailDraft {

    @Id
    private UUID id;

    @Column(name = "owner_agent_sub", nullable = false, length = 64)
    private String ownerAgentSub;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "event_id")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DraftPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DraftTone tone;

    @Column(name = "subject_template", nullable = false, length = 120)
    private String subjectTemplate;

    @Column(name = "body_template", nullable = false, length = 5000)
    private String bodyTemplate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DraftSource source;

    @Column(name = "source_reason", length = 40)
    private String sourceReason;

    @Column(name = "model_id", length = 100)
    private String modelId;

    @Column(name = "prompt_version", length = 40)
    private String promptVersion;

    @Column(name = "holiday_id")
    private UUID holidayId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DraftStatus status;

    @Column(name = "ses_message_id", length = 200)
    private String sesMessageId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    EmailDraft(UUID id, String ownerAgentSub, UUID clientId, UUID eventId, DraftPurpose purpose, DraftTone tone,
            GeneratedDraft generated, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.ownerAgentSub = Objects.requireNonNull(ownerAgentSub);
        this.clientId = Objects.requireNonNull(clientId);
        this.eventId = eventId;
        this.purpose = Objects.requireNonNull(purpose);
        this.tone = Objects.requireNonNull(tone);
        this.subjectTemplate = generated.subject();
        this.bodyTemplate = generated.body();
        this.source = generated.source();
        this.sourceReason = generated.sourceReason();
        this.modelId = generated.modelId();
        this.promptVersion = generated.promptVersion();
        this.holidayId = generated.holidayId();
        this.status = DraftStatus.DRAFT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void edit(String subject, String body, Instant now) {
        subjectTemplate = subject;
        bodyTemplate = body;
        updatedAt = now;
    }

    void discard(Instant now) {
        status = DraftStatus.DISCARDED;
        updatedAt = now;
    }

    void sent(String messageId, Instant now) {
        status = DraftStatus.SENT;
        sesMessageId = messageId;
        sentAt = now;
        updatedAt = now;
    }

    UUID getId() { return id; }
    String getOwnerAgentSub() { return ownerAgentSub; }
    UUID getClientId() { return clientId; }
    UUID getEventId() { return eventId; }
    DraftPurpose getPurpose() { return purpose; }
    DraftTone getTone() { return tone; }
    String getSubjectTemplate() { return subjectTemplate; }
    String getBodyTemplate() { return bodyTemplate; }
    DraftSource getSource() { return source; }
    String getSourceReason() { return sourceReason; }
    String getModelId() { return modelId; }
    String getPromptVersion() { return promptVersion; }
    UUID getHolidayId() { return holidayId; }
    DraftStatus getStatus() { return status; }
    Instant getSentAt() { return sentAt; }
    int getVersion() { return version; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
}
