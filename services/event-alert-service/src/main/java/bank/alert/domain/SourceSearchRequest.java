package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One "search now" request made when an agent added a search domain (ADR-0080). */
@Entity
@Table(name = "source_search_request")
public class SourceSearchRequest {

    @Id
    private UUID id;

    @Column(name = "instrument_id", nullable = false, length = 32)
    private String instrumentId;

    @Column(nullable = false, length = 253)
    private String domain;

    @Column(name = "requested_by_subject", nullable = false, length = 128)
    private String requestedBySubject;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(length = 200)
    private String detail;

    protected SourceSearchRequest() {
    }

    public SourceSearchRequest(UUID id, String instrumentId, String domain, String requestedBySubject,
            Instant requestedAt, String status, String detail) {
        this.id = id;
        this.instrumentId = instrumentId;
        this.domain = domain;
        this.requestedBySubject = requestedBySubject;
        this.requestedAt = requestedAt;
        this.status = status;
        this.detail = detail;
    }

    public UUID getId() {
        return id;
    }

    public String getInstrumentId() {
        return instrumentId;
    }

    public String getDomain() {
        return domain;
    }

    public String getRequestedBySubject() {
        return requestedBySubject;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }
}
