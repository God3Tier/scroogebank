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
 * stored (ADR-0054 decision 6, ADR-0053 decision 7). Sending or discarding
 * clears them, so what the agent typed is kept only while the draft is open
 * (ADR-0068 decision 4).
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

    @Column(name = "health_task_id", length = 128)
    private String healthTaskId;

    @Column(name = "alert_id")
    private UUID alertId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DraftPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DraftTone tone;

    /** Null once the draft is sent or discarded. */
    @Column(name = "subject_template", length = 120)
    private String subjectTemplate;

    /** Null once the draft is sent or discarded. */
    @Column(name = "body_template", length = 5000)
    private String bodyTemplate;

    /** Whether the agent changed the generated text, for the send's audit event. */
    @Column(nullable = false)
    private boolean edited;

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

    EmailDraft(UUID id, String ownerAgentSub, DraftInput input, GeneratedDraft generated, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.ownerAgentSub = Objects.requireNonNull(ownerAgentSub);
        this.clientId = Objects.requireNonNull(input.clientId());
        this.eventId = input.eventId();
        this.healthTaskId = input.healthTaskId();
        this.alertId = input.alertId();
        this.purpose = Objects.requireNonNull(input.purpose());
        this.tone = Objects.requireNonNull(input.tone());
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

    /**
     * A News Alert draft (news-alert-drafts plan, section 5.5): one client, built from approved reports with no model
     * call, so its purpose, tone and source are fixed.
     */
    static EmailDraft news(UUID id, String ownerAgentSub, UUID clientId, String subject, String body, Instant now) {
        EmailDraft draft = new EmailDraft();
        draft.id = Objects.requireNonNull(id);
        draft.ownerAgentSub = Objects.requireNonNull(ownerAgentSub);
        draft.clientId = Objects.requireNonNull(clientId);
        draft.purpose = DraftPurpose.NEWS_ALERT;
        draft.tone = DraftTone.NONE;
        draft.source = DraftSource.NEWS;
        draft.subjectTemplate = subject;
        draft.bodyTemplate = body;
        draft.status = DraftStatus.DRAFT;
        draft.createdAt = now;
        draft.updatedAt = now;
        return draft;
    }

    /** The service rebuilt the text after the reports changed; this is not the agent's edit. */
    void rebuilt(String subject, String body, Instant now) {
        subjectTemplate = subject;
        bodyTemplate = body;
        updatedAt = now;
    }

    boolean isNews() { return purpose == DraftPurpose.NEWS_ALERT; }

    void edit(String subject, String body, Instant now) {
        if (!subject.equals(subjectTemplate) || !body.equals(bodyTemplate)) {
            edited = true;
        }
        subjectTemplate = subject;
        bodyTemplate = body;
        updatedAt = now;
    }

    void discard(Instant now) {
        status = DraftStatus.DISCARDED;
        clearText();
        updatedAt = now;
    }

    void sent(String messageId, Instant now) {
        status = DraftStatus.SENT;
        sesMessageId = messageId;
        sentAt = now;
        clearText();
        updatedAt = now;
    }

    /** The agent keeps their copy through the Bcc; nothing they typed stays here. */
    private void clearText() {
        subjectTemplate = null;
        bodyTemplate = null;
    }

    boolean hasText() { return subjectTemplate != null && bodyTemplate != null; }

    UUID getId() { return id; }
    String getOwnerAgentSub() { return ownerAgentSub; }
    UUID getClientId() { return clientId; }
    UUID getEventId() { return eventId; }
    String getHealthTaskId() { return healthTaskId; }
    UUID getAlertId() { return alertId; }
    boolean isEdited() { return edited; }
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
    String getSesMessageId() { return sesMessageId; }
    int getVersion() { return version; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
}
