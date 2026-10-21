package bank.engagement.holidays;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps client-service's free-text {@code country} to an ISO 3166-1 alpha-2
 * code (ADR-0054 decision 8). Only countries with holiday data are listed; any
 * other country gets no holidays. More countries are open question Q5.
 */
public final class CountryCodes {

    private static final Map<String, String> CODES = Map.of(
            "SG", "SG",
            "SGP", "SG",
            "SINGAPORE", "SG",
            "REPUBLIC OF SINGAPORE", "SG");

    private CountryCodes() {
    }

    public static Optional<String> toIso(String country) {
        if (country == null) {
            return Optional.empty();
        }
        // Punctuation is ignored, so "Singapore." and "S.G." match too.
        String key = country.replaceAll("[^\\p{L}\\s]", "").strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return Optional.ofNullable(CODES.get(key));
    }
}
