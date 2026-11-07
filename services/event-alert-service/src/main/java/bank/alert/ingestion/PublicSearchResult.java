package bank.alert.ingestion;

import java.time.Instant;

public record PublicSearchResult(
        String providerResultId,
        String url,
        String title,
        Instant publishedAt,
        Instant retrievedAt,
        String contentHash,
        String snippet) {

    /** The most public text kept per result: ADR-0011 allows only the minimum excerpt. */
    public static final int MAX_SNIPPET = 1000;

    /** Result with no snippet, for providers that return only a title and a link. */
    public PublicSearchResult(String providerResultId, String url, String title, Instant publishedAt,
            Instant retrievedAt, String contentHash) {
        this(providerResultId, url, title, publishedAt, retrievedAt, contentHash, null);
    }

    public PublicSearchResult {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        if (title == null || title.isBlank() || title.length() > 300) {
            throw new IllegalArgumentException("title must contain 1 to 300 characters");
        }
        if (retrievedAt == null) throw new IllegalArgumentException("retrievedAt is required");
        if (snippet != null && snippet.length() > MAX_SNIPPET) {
            throw new IllegalArgumentException("snippet exceeds " + MAX_SNIPPET + " characters");
        }
        if (providerResultId != null && providerResultId.length() > 128) {
            throw new IllegalArgumentException("providerResultId exceeds 128 characters");
        }
    }
}
