package bank.alert.ai;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PublicEvidence(String referenceId, String title, String excerpt,
        String canonicalUrl, String sourceDomain, Instant publishedAt,
        List<String> candidateInstrumentIds) {
    public PublicEvidence {
        referenceId = require(referenceId, "referenceId", 64);
        title = require(title, "title", 300);
        excerpt = require(excerpt, "excerpt", 4_000);
        canonicalUrl = require(canonicalUrl, "canonicalUrl", 2_048);
        sourceDomain = require(sourceDomain, "sourceDomain", 253);
        candidateInstrumentIds = List.copyOf(Objects.requireNonNull(candidateInstrumentIds));
        if (candidateInstrumentIds.isEmpty()) throw new IllegalArgumentException("candidateInstrumentIds cannot be empty");
    }

    private static String require(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximum + " characters");
        }
        return value.strip();
    }
}
