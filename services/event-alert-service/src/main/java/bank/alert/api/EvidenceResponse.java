package bank.alert.api;

import java.time.Instant;

public record EvidenceResponse(
        String title,
        String url,
        String sourceDomain,
        Instant publishedAt,
        Instant retrievedAt) {
}
