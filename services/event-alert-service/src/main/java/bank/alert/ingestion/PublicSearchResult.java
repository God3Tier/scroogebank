package bank.alert.ingestion;

import java.time.Instant;

public record PublicSearchResult(
        String providerResultId,
        String url,
        String title,
        Instant publishedAt,
        Instant retrievedAt,
        String contentHash) {

    public PublicSearchResult {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        if (title == null || title.isBlank() || title.length() > 300) {
            throw new IllegalArgumentException("title must contain 1 to 300 characters");
        }
        if (retrievedAt == null) throw new IllegalArgumentException("retrievedAt is required");
        if (providerResultId != null && providerResultId.length() > 128) {
            throw new IllegalArgumentException("providerResultId exceeds 128 characters");
        }
    }
}
