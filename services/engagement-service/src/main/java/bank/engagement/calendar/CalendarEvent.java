package bank.engagement.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An agent's calendar event. Title, description and location are what the
 * agent typed: they go only to the agent's own mailbox and are never logged,
 * audited or sent to a model (agent-engagement plan §15).
 */
@Entity
@Table(name = "events")
class CalendarEvent {

    @Id
    private UUID id;

    @Column(name = "owner_agent_sub", nullable = false, length = 64)
    private String ownerAgentSub;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(length = 200)
    private String location;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    /** The iCalendar {@code SEQUENCE} of the latest invite (ADR-0052 decision 1). */
    @Column(name = "ics_sequence", nullable = false)
    private int icsSequence;

    /**
     * Raised by every update and the cancel, even one that changes only the
     * reminders. Mail rows record it, so the event shows the reminders of its
     * latest change.
     */
    @Column(nullable = false)
    private int revision;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CalendarEvent() {
    }

    CalendarEvent(UUID id, String ownerAgentSub, Details details, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.ownerAgentSub = Objects.requireNonNull(ownerAgentSub);
        this.status = EventStatus.SCHEDULED;
        this.createdAt = now;
        apply(details, now);
    }

    /** The fields an agent sets. */
    record Details(UUID clientId, String title, String description, String location, Instant startsAt,
            Instant endsAt) {
    }

    /**
     * Applies new details and returns the names of the fields that changed.
     * Changing the time, title, location or description needs a new invite.
     */
    List<String> apply(Details details, Instant now) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(clientId, details.clientId())) changed.add("clientId");
        if (!Objects.equals(title, details.title())) changed.add("title");
        if (!Objects.equals(description, details.description())) changed.add("description");
        if (!Objects.equals(location, details.location())) changed.add("location");
        if (!Objects.equals(startsAt, details.startsAt())) changed.add("startsAt");
        if (!Objects.equals(endsAt, details.endsAt())) changed.add("endsAt");
        clientId = details.clientId();
        title = details.title();
        description = details.description();
        location = details.location();
        startsAt = details.startsAt();
        endsAt = details.endsAt();
        updatedAt = now;
        return changed;
    }

    static boolean needsNewInvite(List<String> changedFields) {
        return changedFields.stream().anyMatch(field -> List.of("title", "description", "location", "startsAt",
                "endsAt").contains(field));
    }

    void nextSequence() {
        icsSequence++;
    }

    void nextRevision() {
        revision++;
    }

    void cancel(Instant now) {
        status = EventStatus.CANCELLED;
        icsSequence++;
        revision++;
        updatedAt = now;
    }

    boolean isCancelled() {
        return status == EventStatus.CANCELLED;
    }

    UUID getId() { return id; }
    String getOwnerAgentSub() { return ownerAgentSub; }
    UUID getClientId() { return clientId; }
    String getTitle() { return title; }
    String getDescription() { return description; }
    String getLocation() { return location; }
    Instant getStartsAt() { return startsAt; }
    Instant getEndsAt() { return endsAt; }
    EventStatus getStatus() { return status; }
    int getIcsSequence() { return icsSequence; }
    int getRevision() { return revision; }
    int getVersion() { return version; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
}
