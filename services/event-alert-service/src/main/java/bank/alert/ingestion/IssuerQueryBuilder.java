package bank.alert.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class IssuerQueryBuilder {

    /**
     * Exactly two searches for every instrument, one per query group (ADR-0084). The 2026-10-31 Tavily experiment
     * found about twice as many usable stories this way as with one mixed search. Adding domains to a request
     * does not add searches.
     */
    static final List<String> PERFORMANCE_AND_CREDIT = List.of(
            "earnings", "results", "profit", "revenue", "guidance", "dividend",
            "credit rating", "downgrade", "upgrade", "default", "credit outlook");
    static final List<String> CORPORATE_AND_EXTERNAL_EVENTS = List.of(
            "merger", "acquisition", "takeover", "divestment", "buyback", "regulatory action",
            "investigation", "fine", "filing", "approval", "appointment", "resignation", "steps down");
    private static final List<List<String>> TOPIC_TERMS = List.of(PERFORMANCE_AND_CREDIT, CORPORATE_AND_EXTERNAL_EVENTS);

    public static final int QUERIES_PER_INSTRUMENT = TOPIC_TERMS.size();

    /** Two searches for every instrument given; there is no cap on how many instruments (ADR-0084). */
    public List<IssuerSearchQuery> build(List<InstrumentSearchTerms> instruments, List<String> sharedDomains) {
        if (instruments == null || instruments.isEmpty()) return List.of();

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
