package bank.alert.report;

import bank.alert.report.EventSourceSelector.Candidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventSourceSelectorTest {

    private static Candidate source(String id, String domain, String publishedAt, String url) {
        return new Candidate(id, domain, publishedAt == null ? null : Instant.parse(publishedAt), url);
    }

    @Test
    void takesAtMostFiveSources() {
        List<Candidate> linked = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            linked.add(source("s" + i, "site" + i + ".example", "2026-11-0" + (i + 1) + "T00:00:00Z", "https://site" + i + ".example/a"));
        }

        List<Candidate> chosen = EventSourceSelector.select(linked);

        assertThat(chosen).hasSize(5).extracting(Candidate::sourceId).containsExactly("s0", "s1", "s2", "s3", "s4");
    }

    @Test
    void takesOneSourcePerDomainTheEarliestPublished() {
        List<Candidate> linked = List.of(
                source("late", "news.example", "2026-11-03T00:00:00Z", "https://news.example/late"),
                source("early", "news.example", "2026-11-01T00:00:00Z", "https://news.example/early"),
                source("other", "other.example", "2026-11-02T00:00:00Z", "https://other.example/x"));

        assertThat(EventSourceSelector.select(linked)).extracting(Candidate::sourceId).containsExactly("early", "other");
    }

    @Test
    void domainsAreComparedIgnoringCase() {
        List<Candidate> linked = List.of(
                source("a", "News.Example", "2026-11-01T00:00:00Z", "https://news.example/a"),
                source("b", "news.example", "2026-11-02T00:00:00Z", "https://news.example/b"));

        assertThat(EventSourceSelector.select(linked)).extracting(Candidate::sourceId).containsExactly("a");
    }

    @Test
    void aTieOnPublicationTimeIsBrokenByCanonicalUrl() {
        List<Candidate> linked = List.of(
                source("b", "news.example", "2026-11-01T00:00:00Z", "https://news.example/b"),
                source("a", "news.example", "2026-11-01T00:00:00Z", "https://news.example/a"));

        assertThat(EventSourceSelector.select(linked)).extracting(Candidate::sourceId).containsExactly("a");
    }

    @Test
    void aSourceWithNoPublicationTimeComesLast() {
        List<Candidate> linked = List.of(
                source("undated", "a.example", null, "https://a.example/u"),
                source("dated", "b.example", "2026-11-01T00:00:00Z", "https://b.example/d"));

        assertThat(EventSourceSelector.select(linked)).extracting(Candidate::sourceId).containsExactly("dated", "undated");
    }

    @Test
    void theChoiceDoesNotDependOnTheOrderTheyAreGivenIn() {
        List<Candidate> linked = new ArrayList<>(List.of(
                source("1", "a.example", "2026-11-01T00:00:00Z", "https://a.example/1"),
                source("2", "b.example", "2026-11-02T00:00:00Z", "https://b.example/2"),
                source("3", "c.example", "2026-11-03T00:00:00Z", "https://c.example/3")));
        List<Candidate> expected = EventSourceSelector.select(linked);

        Collections.reverse(linked);

        assertThat(EventSourceSelector.select(linked)).isEqualTo(expected);
    }
}
