package bank.alert.matching;

import bank.alert.domain.Instrument;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic matching of a public article to the instrument master (ADR-0032
 * decision 4, ADR-0049): an exact ticker, a named issuer, or a named sector. The
 * same text and master always give the same matches, each with the reason it
 * matched. No model and no similarity score is involved, and an instrument that
 * is not named is never guessed. Relevance feeds the materiality score's
 * retrieval-relevance input: direct beats sector-level.
 */
@Component
public class InstrumentMatcher {

    public static final String VERSION = "matcher-v1";
    public static final BigDecimal TICKER_AND_ISSUER = new BigDecimal("1.00");
    public static final BigDecimal TICKER_OR_ISSUER = new BigDecimal("0.80");
    public static final BigDecimal SECTOR_ONLY = new BigDecimal("0.40");

    /** Matches, sorted by instrument ID; inactive instruments are never matched. */
    public List<InstrumentMatch> match(String title, String excerpt, List<Instrument> instruments) {
        String text = (title == null ? "" : title) + " " + (excerpt == null ? "" : excerpt);
        String normalized = " " + normalize(text) + " ";
        List<InstrumentMatch> matches = new ArrayList<>();
        for (Instrument instrument : instruments) {
            if (!instrument.isActive()) continue;
            boolean ticker = containsTicker(text, instrument.getTicker());
            boolean issuer = containsPhrase(normalized, instrument.getIssuerName());
            boolean sector = containsPhrase(normalized, instrument.getSector());
            if (ticker && issuer) {
                matches.add(new InstrumentMatch(instrument.getId(), TICKER_AND_ISSUER, "TICKER_AND_ISSUER"));
            } else if (ticker) {
                matches.add(new InstrumentMatch(instrument.getId(), TICKER_OR_ISSUER, "TICKER"));
            } else if (issuer) {
                matches.add(new InstrumentMatch(instrument.getId(), TICKER_OR_ISSUER, "ISSUER"));
            } else if (sector) {
                matches.add(new InstrumentMatch(instrument.getId(), SECTOR_ONLY, "SECTOR"));
            }
        }
        matches.sort(Comparator.comparing(InstrumentMatch::instrumentId));
        return List.copyOf(matches);
    }

    /** A ticker is an exact, case-sensitive token: SYN1 matches "SYN1 rose", not "ASYN1" or "syn1". */
    private static boolean containsTicker(String text, String ticker) {
        if (ticker == null || ticker.isBlank()) return false;
        return Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(ticker.strip()) + "(?![A-Za-z0-9])")
                .matcher(text).find();
    }

    private static boolean containsPhrase(String normalizedText, String phrase) {
        String needle = normalize(phrase == null ? "" : phrase);
        return !needle.isEmpty() && normalizedText.contains(" " + needle + " ");
    }

    /** Lower-case words only: punctuation and repeated spaces never change a match. */
    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
