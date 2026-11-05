package bank.alert.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chooses which of an event's linked sources the model sees (ADR-0084): at most five, from distinct domains. For
 * each domain the earliest-published source is taken, ties broken by canonical URL; the five are then ordered by
 * publication time and canonical URL. The same sources always give the same choice, so a report only changes when
 * the sources do.
 */
public final class EventSourceSelector {

    public static final int MAX_SOURCES = 5;

    private static final Comparator<Candidate> BY_TIME_THEN_URL = Comparator
            .comparing(Candidate::publishedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Candidate::canonicalUrl);

    private EventSourceSelector() {
    }

    /** A linked source, as far as selection is concerned. */
    public record Candidate(String sourceId, String domain, Instant publishedAt, String canonicalUrl) {}

    public static List<Candidate> select(List<Candidate> linked) {
        Map<String, Candidate> bestPerDomain = new LinkedHashMap<>();
        for (Candidate candidate : linked.stream().sorted(BY_TIME_THEN_URL).toList()) {
            bestPerDomain.putIfAbsent(candidate.domain().toLowerCase(java.util.Locale.ROOT), candidate);
        }
        List<Candidate> chosen = new ArrayList<>(bestPerDomain.values());
        chosen.sort(BY_TIME_THEN_URL);
        return List.copyOf(chosen.subList(0, Math.min(MAX_SOURCES, chosen.size())));
    }
}
