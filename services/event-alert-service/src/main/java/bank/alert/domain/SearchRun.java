package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "search_run")
public class SearchRun {

    @Id
    private UUID id;

    @Column(name = "window_from", nullable = false)
    private Instant windowFrom;

    @Column(name = "window_to", nullable = false)
    private Instant windowTo;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "search_count", nullable = false)
    private int searchCount;

    @Column(name = "result_count", nullable = false)
    private int resultCount;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected SearchRun() {
    }

    public SearchRun(UUID id, Instant windowFrom, Instant windowTo, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.windowFrom = Objects.requireNonNull(windowFrom);
        this.windowTo = Objects.requireNonNull(windowTo);
        this.createdAt = Objects.requireNonNull(createdAt);
        if (!windowFrom.isBefore(windowTo)) {
            throw new IllegalArgumentException("search window start must precede its end");
        }
        this.status = "RUNNING";
    }

    public void recordSearchAttempts(int attempts) {
        if (attempts < 0) throw new IllegalArgumentException("attempts cannot be negative");
        searchCount += attempts;
    }

    public void restart() {
        status = "RUNNING";
        searchCount = 0;
        resultCount = 0;
        errorCode = null;
        completedAt = null;
    }

    public void claimUntil(Instant leaseUntil) {
        this.leaseUntil = Objects.requireNonNull(leaseUntil);
    }

    public boolean hasActiveLeaseAt(Instant instant) {
        return "RUNNING".equals(status) && leaseUntil != null && leaseUntil.isAfter(instant);
    }

    public void succeed(int acceptedResults, Instant completedAt) {
        if (acceptedResults < 0) throw new IllegalArgumentException("accepted results cannot be negative");
        this.resultCount = acceptedResults;
        this.status = "SUCCEEDED";
        this.errorCode = null;
        this.completedAt = Objects.requireNonNull(completedAt);
    }

    public void fail(String errorCode, Instant completedAt) {
        this.status = "FAILED";
        this.errorCode = Objects.requireNonNull(errorCode);
        this.completedAt = Objects.requireNonNull(completedAt);
    }

    public UUID getId() { return id; }
    public Instant getWindowFrom() { return windowFrom; }
    public Instant getWindowTo() { return windowTo; }
    public String getStatus() { return status; }
    public int getSearchCount() { return searchCount; }
    public int getResultCount() { return resultCount; }
    public String getErrorCode() { return errorCode; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
