package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A search domain an agent added for one company (ADR-0079). Deactivating keeps the row. */
@Entity
@Table(name = "instrument_source_domain")
public class InstrumentSourceDomain {

    @Id
    private UUID id;

    @Column(name = "instrument_id", nullable = false, length = 32)
    private String instrumentId;

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

    protected InstrumentSourceDomain() {
    }

    public InstrumentSourceDomain(UUID id, String instrumentId, String domain, String addedBySubject, Instant addedAt) {
        this.id = id;
        this.instrumentId = instrumentId;
        this.domain = domain;
        this.addedBySubject = addedBySubject;
        this.addedAt = addedAt;
    }

    public void deactivate(String actorSubject, Instant at) {
        this.active = false;
        this.deactivatedBySubject = actorSubject;
        this.deactivatedAt = at;
    }

    public UUID getId() { return id; }
    public String getInstrumentId() { return instrumentId; }
    public String getDomain() { return domain; }
    public String getAddedBySubject() { return addedBySubject; }
    public Instant getAddedAt() { return addedAt; }
    public boolean isActive() { return active; }
    public String getDeactivatedBySubject() { return deactivatedBySubject; }
    public Instant getDeactivatedAt() { return deactivatedAt; }
}
