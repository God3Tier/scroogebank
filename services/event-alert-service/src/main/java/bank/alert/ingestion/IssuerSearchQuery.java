package bank.alert.ingestion;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record IssuerSearchQuery(
        String query,
        List<String> issuerTerms,
        List<String> eventTerms,
        List<String> issuerDomains,
        List<String> approvedDomains) {

    private static final Set<String> NON_EVENT_TITLE_PHRASES = Set.of(
            "ahead of earnings", "earnings playbook", "earnings season",
            "key to gauging", "set to report", "expected to report", "will report", "will land",
            "what to expect", "what to watch");

    public IssuerSearchQuery {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        issuerTerms = List.copyOf(Objects.requireNonNull(issuerTerms));
        eventTerms = List.copyOf(Objects.requireNonNull(eventTerms));
        issuerDomains = List.copyOf(Objects.requireNonNull(issuerDomains));
        approvedDomains = List.copyOf(Objects.requireNonNull(approvedDomains));
        if (issuerTerms.isEmpty() || eventTerms.isEmpty() || approvedDomains.isEmpty()) {
            throw new IllegalArgumentException("issuer, event, and domain terms are required");
        }
    }

    public boolean accepts(PublicSearchResult result) {
        String title = normalize(result.title());
        if (NON_EVENT_TITLE_PHRASES.stream().anyMatch(title::contains)) return false;
        boolean eventInTitle = eventTerms.stream().map(IssuerSearchQuery::normalize)
                .anyMatch(term -> contains(title, term));
        if (!eventInTitle) return false;
        if (containsIssuer(title)) return true;
        return isIssuerDomain(result.url())
                && containsIssuer(normalize(result.title() + " " + Objects.toString(result.snippet(), "")));
    }

    private boolean containsIssuer(String text) {
        return issuerTerms.stream().map(IssuerSearchQuery::normalize).anyMatch(term -> contains(text, term));
    }

    private boolean isIssuerDomain(String value) {
        try {
            String host = URI.create(value).getHost();
            if (host == null) return false;
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return issuerDomains.stream().anyMatch(domain -> normalizedHost.equals(domain)
                    || normalizedHost.endsWith("." + domain));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean contains(String text, String term) {
        return !term.isEmpty() && (" " + text + " ").contains(" " + term + " ");
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
