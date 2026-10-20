package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "source_result")
public class SourceResult {

    @Id
    private UUID id;

    @Column(name = "search_run_id", nullable = false)
    private UUID searchRunId;

    @Column(name = "provider_result_id", length = 128)
    private String providerResultId;

    @Column(name = "canonical_url", nullable = false, length = 2048)
    private String canonicalUrl;

    @Column(name = "canonical_url_hash", nullable = false, length = 64, unique = true,
            columnDefinition = "char(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String canonicalUrlHash;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "source_domain", nullable = false, length = 253)
    private String sourceDomain;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "retrieved_at", nullable = false)
    private Instant retrievedAt;

    @Column(name = "content_hash", length = 64, columnDefinition = "char(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String contentHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(length = 1000)
    private String excerpt;
    @Column(name = "processed_at")
    private Instant processedAt;
    @Column(name = "processing_outcome", length = 40)
    private String processingOutcome;

    protected SourceResult() {
    }

    public SourceResult(UUID id, UUID searchRunId, String providerResultId, String canonicalUrl,
            String canonicalUrlHash, String title, String sourceDomain, Instant publishedAt,
            Instant retrievedAt, String contentHash, Instant expiresAt) {
        this.id = Objects.requireNonNull(id);
        this.searchRunId = Objects.requireNonNull(searchRunId);
        this.providerResultId = providerResultId;
        this.canonicalUrl = Objects.requireNonNull(canonicalUrl);
        this.canonicalUrlHash = Objects.requireNonNull(canonicalUrlHash);
        this.title = Objects.requireNonNull(title);
        this.sourceDomain = Objects.requireNonNull(sourceDomain);
        this.publishedAt = publishedAt;
        this.retrievedAt = Objects.requireNonNull(retrievedAt);
        this.contentHash = contentHash;
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public SourceResult(UUID id, UUID searchRunId, String providerResultId, String canonicalUrl,
            String canonicalUrlHash, String title, String sourceDomain, Instant publishedAt,
            Instant retrievedAt, String contentHash, Instant expiresAt, String excerpt) {
        this(id, searchRunId, providerResultId, canonicalUrl, canonicalUrlHash, title, sourceDomain,
                publishedAt, retrievedAt, contentHash, expiresAt);
        if (excerpt != null && excerpt.length() > 1000) {
            throw new IllegalArgumentException("excerpt exceeds 1000 characters");
        }
        this.excerpt = excerpt == null || excerpt.isBlank() ? null : excerpt;
    }

    /** Records the pipeline's definitive outcome; the source is not processed again. */
    public void markProcessed(String outcome, Instant at) {
        this.processingOutcome = Objects.requireNonNull(outcome);
        this.processedAt = Objects.requireNonNull(at);
    }

    public UUID getId() { return id; }
    public UUID getSearchRunId() { return searchRunId; }
    public String getProviderResultId() { return providerResultId; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public String getCanonicalUrlHash() { return canonicalUrlHash; }
    public String getTitle() { return title; }
    public String getSourceDomain() { return sourceDomain; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getRetrievedAt() { return retrievedAt; }
    public String getContentHash() { return contentHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getExcerpt() { return excerpt; }
    public Instant getProcessedAt() { return processedAt; }
    public String getProcessingOutcome() { return processingOutcome; }
}
