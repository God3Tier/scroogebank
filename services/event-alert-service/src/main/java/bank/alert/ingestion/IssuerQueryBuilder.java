package bank.alert.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class IssuerQueryBuilder {

    private static final List<String> DISCOVERY_TERMS = List.of(
            "earnings", "results", "guidance", "merger", "acquisition",
            "regulatory", "investigation", "credit rating", "appointment", "resignation");

    public List<IssuerSearchQuery> build(List<InstrumentSearchTerms> instruments,
            List<String> sharedDomains, int maximumQueries) {
        if (maximumQueries < 1 || maximumQueries > 4) {
            throw new IllegalArgumentException("maximumQueries must be between 1 and 4");
        }
        if (instruments == null || instruments.isEmpty()) return List.of();
        if (instruments.size() > maximumQueries) {
            throw new IllegalArgumentException("instrument catalogue exceeds the bounded query capacity");
        }

        List<IssuerSearchQuery> queries = new ArrayList<>();
        for (InstrumentSearchTerms instrument : instruments) {
            LinkedHashSet<String> publicTerms = new LinkedHashSet<>();
            publicTerms.add(instrument.issuer());
            publicTerms.add(instrument.ticker());
            publicTerms.addAll(instrument.aliases());
            publicTerms.addAll(DISCOVERY_TERMS);
            LinkedHashSet<String> domains = new LinkedHashSet<>(instrument.officialDomains());
            domains.addAll(sharedDomains);
            List<String> issuerTerms = new ArrayList<>();
            issuerTerms.add(instrument.issuer());
            issuerTerms.addAll(instrument.aliases());
            queries.add(new IssuerSearchQuery(
                    String.join(" ", publicTerms),
                    issuerTerms, instrument.eventTerms(), instrument.officialDomains(), List.copyOf(domains)));
        }
        return List.copyOf(queries);
    }
}
