package bank.alert.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chooses which of an event's linked sources the model sees (ADR-0084): at most five, from distinct domains. For
 * each domain the newest-published source is taken (a ticker's event holds only the latest run's articles, and the
 * report must reflect the latest news, not a preview of it), ties broken by canonical URL; when there are more than
 * five domains the five newest win; the five are then ordered by publication time and canonical URL. The same sources
 * always give the same choice.
 */
public final class EventSourceSelector {

    public static final int MAX_SOURCES = 5;

    private static final Comparator<Candidate> BY_TIME_THEN_URL = Comparator
            .comparing(Candidate::publishedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Candidate::canonicalUrl);

    private static final Comparator<Candidate> NEWEST_FIRST = Comparator
            .comparing(Candidate::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Candidate::canonicalUrl);

    private EventSourceSelector() {
    }

    /** A linked source, as far as selection is concerned. */
    public record Candidate(String sourceId, String domain, Instant publishedAt, String canonicalUrl) {}

    public static List<Candidate> select(List<Candidate> linked) {
        Map<String, Candidate> newestPerDomain = new LinkedHashMap<>();
        for (Candidate candidate : linked.stream().sorted(NEWEST_FIRST).toList()) {
            newestPerDomain.putIfAbsent(candidate.domain().toLowerCase(java.util.Locale.ROOT), candidate);
        }
        List<Candidate> newest = new ArrayList<>(newestPerDomain.values());
        List<Candidate> chosen = new ArrayList<>(newest.subList(0, Math.min(MAX_SOURCES, newest.size())));
        chosen.sort(BY_TIME_THEN_URL);
        return List.copyOf(chosen);
    }
}
