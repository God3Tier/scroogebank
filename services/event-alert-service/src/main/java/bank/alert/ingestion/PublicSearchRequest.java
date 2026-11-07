package bank.alert.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PublicSearchRequest(
        String query,
        List<String> approvedDomains,
        Instant publishedFrom,
        Instant publishedTo,
        int maximumResults) {

    public PublicSearchRequest {
        if (query == null || query.isBlank() || query.length() > 4000) {
            throw new IllegalArgumentException("query must contain 1 to 4000 characters");
        }
        approvedDomains = List.copyOf(Objects.requireNonNull(approvedDomains));
        if (approvedDomains.isEmpty()) throw new IllegalArgumentException("approvedDomains cannot be empty");
        Objects.requireNonNull(publishedFrom);
        Objects.requireNonNull(publishedTo);
        if (!publishedFrom.isBefore(publishedTo)) throw new IllegalArgumentException("invalid publication window");
        if (maximumResults < 1 || maximumResults > 20) {
            throw new IllegalArgumentException("maximumResults must be between 1 and 20");
        }
    }
}
