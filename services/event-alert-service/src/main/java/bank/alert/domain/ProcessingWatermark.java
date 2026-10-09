package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "processing_watermark")
public class ProcessingWatermark {

    @Id
    @Column(length = 64)
    private String provider;

    @Column(name = "last_successful_to", nullable = false)
    private Instant lastSuccessfulTo;

    @Column(name = "overlap_seconds", nullable = false)
    private int overlapSeconds;

    @Version
    @Column(nullable = false)
    private long version;

    protected ProcessingWatermark() {
    }

    public ProcessingWatermark(String provider, Instant lastSuccessfulTo, Duration overlap) {
        this.provider = Objects.requireNonNull(provider);
        this.lastSuccessfulTo = Objects.requireNonNull(lastSuccessfulTo);
        this.overlapSeconds = Math.toIntExact(Objects.requireNonNull(overlap).toSeconds());
    }

    public Instant windowStart() {
        return lastSuccessfulTo.minusSeconds(overlapSeconds);
    }

    public void advanceTo(Instant successfulTo, Duration overlap) {
        Objects.requireNonNull(successfulTo);
        if (successfulTo.isBefore(lastSuccessfulTo)) {
            throw new IllegalArgumentException("watermark cannot move backwards");
        }
        lastSuccessfulTo = successfulTo;
        overlapSeconds = Math.toIntExact(Objects.requireNonNull(overlap).toSeconds());
    }

    public String getProvider() { return provider; }
    public Instant getLastSuccessfulTo() { return lastSuccessfulTo; }
    public int getOverlapSeconds() { return overlapSeconds; }
    public long getVersion() { return version; }
}
