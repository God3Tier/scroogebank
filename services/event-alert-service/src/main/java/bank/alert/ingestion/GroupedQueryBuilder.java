package bank.alert.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class GroupedQueryBuilder {

    private static final int INSTRUMENTS_PER_QUERY = 8;

    public List<String> build(List<InstrumentSearchTerms> instruments, int maximumQueries) {
        if (maximumQueries < 1 || maximumQueries > 4) {
            throw new IllegalArgumentException("maximumQueries must be between 1 and 4");
        }
        if (instruments == null || instruments.isEmpty()) return List.of();
        if (instruments.size() > maximumQueries * INSTRUMENTS_PER_QUERY) {
            throw new IllegalArgumentException("instrument catalogue exceeds the bounded grouped-query capacity");
        }

        List<String> queries = new ArrayList<>();
        for (int start = 0; start < instruments.size() && queries.size() < maximumQueries;
                start += INSTRUMENTS_PER_QUERY) {
            List<InstrumentSearchTerms> group = instruments.subList(
                    start, Math.min(start + INSTRUMENTS_PER_QUERY, instruments.size()));
            LinkedHashSet<String> issuers = new LinkedHashSet<>();
            LinkedHashSet<String> events = new LinkedHashSet<>();
            for (InstrumentSearchTerms instrument : group) {
                issuers.add(quote(instrument.ticker()));
                issuers.add(quote(instrument.issuer()));
                issuers.add(quote(instrument.sector()));
                instrument.aliases().forEach(alias -> issuers.add(quote(alias)));
                events.addAll(instrument.eventTerms());
            }
            queries.add("(" + String.join(" OR ", issuers) + ") ("
                    + String.join(" OR ", events.stream().map(GroupedQueryBuilder::quote).toList()) + ")");
        }
        return List.copyOf(queries);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "") + "\"";
    }
}
