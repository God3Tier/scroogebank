package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

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
        List<String> approvedDomains,
        List<String> eventTerms) {

    public IngestionProperties {
        provider = provider == null || provider.isBlank() ? "agentcore-web-search-1.2.0" : provider;
        overlap = overlap == null ? Duration.ofMinutes(5) : overlap;
        initialLookback = initialLookback == null ? Duration.ofHours(4) : initialLookback;
        maxQueriesPerRun = maxQueriesPerRun <= 0 ? 4 : Math.min(maxQueriesPerRun, 4);
        maxResultsPerQuery = maxResultsPerQuery <= 0 ? 10 : Math.min(maxResultsPerQuery, 10);
        maxAttempts = maxAttempts <= 0 ? 3 : Math.min(maxAttempts, 3);
        retention = retention == null ? Duration.ofDays(90) : retention;
        approvedDomains = approvedDomains == null || approvedDomains.isEmpty()
                ? List.of("mas.gov.sg", "sgx.com")
                : approvedDomains.stream().map(String::toLowerCase).distinct().toList();
        eventTerms = eventTerms == null || eventTerms.isEmpty()
                ? List.of("earnings", "regulatory", "merger", "credit", "leadership")
                : eventTerms.stream().map(String::strip).filter(term -> !term.isEmpty()).distinct().toList();
        if (overlap.isNegative() || overlap.compareTo(initialLookback) >= 0) {
            throw new IllegalArgumentException("alert.ingestion.overlap must be non-negative and shorter than initial-lookback");
        }
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("alert.ingestion.retention must be positive");
        }
        if (eventTerms.isEmpty()) throw new IllegalArgumentException("alert.ingestion.event-terms cannot be empty");
    }
}
