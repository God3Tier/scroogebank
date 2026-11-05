package bank.alert.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class IssuerQueryBuilder {

    /**
     * One focused search per topic for every instrument. The 2026-10-31 Tavily experiment found about twice as
     * many usable stories this way as with one mixed search; two topics found 10 of the 13 that four did, at
     * half the credits.
     */
    private static final List<List<String>> TOPIC_TERMS = List.of(
            List.of("earnings", "results", "financial results", "profit", "revenue", "guidance"),
            List.of("merger", "acquisition", "takeover", "divestment", "buyback"));

    public static final int QUERIES_PER_INSTRUMENT = TOPIC_TERMS.size();

    public List<IssuerSearchQuery> build(List<InstrumentSearchTerms> instruments,
            List<String> sharedDomains, int maximumQueries) {
        if (maximumQueries < 1) {
            throw new IllegalArgumentException("maximumQueries must be at least 1");
        }
        if (instruments == null || instruments.isEmpty()) return List.of();
        if (instruments.size() * QUERIES_PER_INSTRUMENT > maximumQueries) {
            throw new IllegalArgumentException("instrument catalogue exceeds the bounded query capacity");
        }

        List<IssuerSearchQuery> queries = new ArrayList<>();
        for (InstrumentSearchTerms instrument : instruments) {
            LinkedHashSet<String> domains = new LinkedHashSet<>(instrument.officialDomains());
            domains.addAll(sharedDomains);
            // Agent-added domains are searched but are not official, so they get no issuer-domain exception (ADR-0079).
            domains.addAll(instrument.customDomains());
            List<String> issuerTerms = new ArrayList<>();
            issuerTerms.add(instrument.issuer());
            issuerTerms.addAll(instrument.aliases());
            for (List<String> topic : TOPIC_TERMS) {
                LinkedHashSet<String> publicTerms = new LinkedHashSet<>();
                publicTerms.add(instrument.issuer());
                publicTerms.add(instrument.ticker());
                publicTerms.addAll(instrument.aliases());
                publicTerms.addAll(topic);
                queries.add(new IssuerSearchQuery(
                        String.join(" ", publicTerms),
                        issuerTerms, instrument.eventTerms(), instrument.officialDomains(), List.copyOf(domains)));
            }
        }
        return List.copyOf(queries);
    }
}
