package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties(prefix = "alert.ingestion")
public record IngestionProperties(
        boolean enabled,
        String provider,
        Duration overlap,
        Duration initialLookback,
        int maxQueriesPerRun,
        int maxResultsPerQuery,
        int maxAttempts,
        Duration retention,
        BigDecimal minimumSearchClientShare,
        int minimumSearchClientCount,
        List<String> approvedDomains,
        List<String> eventTerms) {

    public IngestionProperties {
        provider = provider == null || provider.isBlank()
                ? "tavily" : provider.strip().toLowerCase(Locale.ROOT);
        overlap = overlap == null ? Duration.ofMinutes(5) : overlap;
        initialLookback = initialLookback == null ? Duration.ofHours(4) : initialLookback;
        maxQueriesPerRun = maxQueriesPerRun <= 0 ? 1_000 : Math.min(maxQueriesPerRun, 1_000); // a sanity ceiling, not a cap (ADR-0084)
        maxResultsPerQuery = maxResultsPerQuery <= 0 ? 20 : Math.min(maxResultsPerQuery, 20);
        maxAttempts = maxAttempts <= 0 ? 3 : Math.min(maxAttempts, 3);
        retention = retention == null ? Duration.ofDays(90) : retention;
        minimumSearchClientShare = minimumSearchClientShare == null
                ? new BigDecimal("0.20") : minimumSearchClientShare;
        minimumSearchClientCount = minimumSearchClientCount <= 0 ? 2 : minimumSearchClientCount;
        approvedDomains = approvedDomains == null ? List.of()
                : approvedDomains.stream().map(String::strip).filter(domain -> !domain.isEmpty())
                        .map(String::toLowerCase).distinct().toList();
        eventTerms = eventTerms == null || eventTerms.isEmpty()
                ? List.of("earnings", "results", "financial results", "profit", "revenue", "guidance",
                        "regulatory action", "investigation", "fine", "filing", "approval",
                        "merger", "acquisition", "takeover", "divestment", "buyback",
                        "credit rating", "downgrade", "upgrade", "default", "credit outlook",
                        "appointment", "appointed", "appoints", "resignation", "resigned", "steps down")
                : eventTerms.stream().map(String::strip).filter(term -> !term.isEmpty()).distinct().toList();
        if (overlap.isNegative() || overlap.compareTo(initialLookback) >= 0) {
            throw new IllegalArgumentException("alert.ingestion.overlap must be non-negative and shorter than initial-lookback");
        }
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("alert.ingestion.retention must be positive");
        }
        if (minimumSearchClientShare.signum() <= 0
                || minimumSearchClientShare.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "alert.ingestion.minimum-search-client-share must be between zero and one");
        }
        if (eventTerms.isEmpty()) throw new IllegalArgumentException("alert.ingestion.event-terms cannot be empty");
    }
}
