package bank.alert.ingestion;

import java.util.List;
import java.util.Objects;

public record InstrumentSearchTerms(
        String ticker,
        String issuer,
        String sector,
        List<String> aliases,
        List<String> eventTerms,
        List<String> officialDomains,
        List<String> customDomains) {

    public InstrumentSearchTerms {
        ticker = requirePublicTerm(ticker, "ticker");
        issuer = requirePublicTerm(issuer, "issuer");
        sector = requirePublicTerm(sector, "sector");
        aliases = copyTerms(aliases);
        eventTerms = copyTerms(eventTerms);
        officialDomains = copyTerms(officialDomains).stream().map(String::toLowerCase).toList();
        customDomains = copyTerms(customDomains).stream().map(String::toLowerCase).toList();
        if (eventTerms.isEmpty()) {
            throw new IllegalArgumentException("at least one event term is required");
        }
    }

    public InstrumentSearchTerms(String ticker, String issuer, String sector,
            List<String> aliases, List<String> eventTerms, List<String> officialDomains) {
        this(ticker, issuer, sector, aliases, eventTerms, officialDomains, List.of());
    }

    public InstrumentSearchTerms(String ticker, String issuer, String sector,
            List<String> aliases, List<String> eventTerms) {
        this(ticker, issuer, sector, aliases, eventTerms, List.of(), List.of());
    }

    private static List<String> copyTerms(List<String> terms) {
        return Objects.requireNonNullElse(terms, List.<String>of()).stream()
                .map(term -> requirePublicTerm(term, "search term"))
                .distinct()
                .toList();
    }

    private static String requirePublicTerm(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException(name + " must contain 1 to 200 characters");
        }
        return value.strip();
    }
}
