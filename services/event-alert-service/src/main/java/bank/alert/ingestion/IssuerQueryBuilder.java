package bank.alert.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class IssuerQueryBuilder {

    /**
     * Exactly two searches for every instrument (ADR-0084), worded after the 2026-11-05 wording experiment. Tavily ranks by
     * meaning and not by required words, so long topic-word lists drowned the company name and returned other companies'
     * earnings. Search 1 is the company's name and nicknames plus a few results words; search 2 is the name and nicknames
     * alone, which lets Tavily return whatever news names the company. The local filter still decides what is kept.
     * Adding domains to a request does not add searches.
     */
    static final String RESULTS_WORDS = "quarterly results net profit";

    public static final int QUERIES_PER_INSTRUMENT = 2;

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
            // The nicknames when there are any, otherwise the full name: the words a headline would use.
            LinkedHashSet<String> names = new LinkedHashSet<>(instrument.aliases());
            if (names.isEmpty()) names.add(instrument.issuer());
            String nameOnly = String.join(" ", names);
            for (String query : List.of(nameOnly + " " + RESULTS_WORDS, nameOnly)) {
                queries.add(new IssuerSearchQuery(
                        query, issuerTerms, instrument.eventTerms(), instrument.officialDomains(), List.copyOf(domains)));
            }
        }
        return List.copyOf(queries);
    }
}
