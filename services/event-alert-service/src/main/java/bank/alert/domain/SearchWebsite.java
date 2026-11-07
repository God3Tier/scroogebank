package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A website agents added that is searched for every company (ADR-0082). Removing keeps the row. */
@Entity
@Table(name = "search_website")
public class SearchWebsite {

    @Id
    private UUID id;

    @Column(nullable = false, length = 253)
    private String domain;

    @Column(name = "added_by_subject", nullable = false, length = 128)
    private String addedBySubject;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "deactivated_by_subject", length = 128)
    private String deactivatedBySubject;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    protected SearchWebsite() {
    }

    public SearchWebsite(UUID id, String domain, String addedBySubject, Instant addedAt) {
        this.id = id;
        this.domain = domain;
        this.addedBySubject = addedBySubject;
        this.addedAt = addedAt;
    }

    public void deactivate(String actorSubject, Instant at) {
        this.active = false;
        this.deactivatedBySubject = actorSubject;
        this.deactivatedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getDomain() {
        return domain;
    }

    public String getAddedBySubject() {
        return addedBySubject;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public boolean isActive() {
        return active;
    }
}
