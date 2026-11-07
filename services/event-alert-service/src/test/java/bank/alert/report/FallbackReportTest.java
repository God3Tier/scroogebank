package bank.alert.report;

import bank.alert.ai.PublicEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The report put together from the sources alone, when the model could not write one (the safe way to fail). */
class FallbackReportTest {

    private static PublicEvidence evidence(String id, String title, String excerpt, String published) {
        return new PublicEvidence(id, title, excerpt, "https://www.cnbc.com/" + id, "www.cnbc.com",
                published == null ? null : Instant.parse(published), List.of("NASDAQ-AAPL"));
    }

    private static final PublicEvidence NEWEST = evidence("s-new", "Apple falls on its latest earnings report - CNBC",
            "Apple shares fell 2% in extended trading after the company guided to a lower margin. Analysts had expected more.",
            "2026-11-03T20:00:00Z");
    private static final PublicEvidence OLDER = evidence("s-old", "Apple heads into the holidays with record revenue - CNBC",
            "Apple reported record revenue of $114.5 billion for the quarter.", "2026-11-02T20:00:00Z");

    @Test
    void usesTheNewestTitleAndTheFirstSentenceOfItsTextAndCitesThem() {
        Optional<ClientReport> report = FallbackReport.build(List.of(OLDER, NEWEST));

        assertThat(report).isPresent();
        ClientReport built = report.get();
        assertThat(built.headline()).isEqualTo("Apple falls on its latest earnings report");
        assertThat(built.executiveSummary()).hasSize(2);
        assertThat(built.executiveSummary().getFirst().text()).isEqualTo("Apple falls on its latest earnings report.");
        assertThat(built.executiveSummary().get(1).text())
                .isEqualTo("Apple shares fell 2% in extended trading after the company guided to a lower margin.");
        assertThat(built.executiveSummary()).allSatisfy(sentence -> assertThat(sentence.sourceRefs()).containsExactly("s-new"));
        assertThat(built.sourceRefs()).containsExactly("s-new");
    }

    @Test
    void writesNothingOfItsOwn_aSectionTheNewsDoesNotFillIsLeftEmpty() {
        ClientReport built = FallbackReport.build(List.of(NEWEST)).orElseThrow();

        assertThat(built.keyFacts()).isEmpty();
        assertThat(built.whyItMatters()).isEmpty();
        assertThat(built.whatToMonitor()).isEmpty();
        assertThat(built.uncertainty()).isEmpty();
        assertThat(built.marketContext()).isNull();
    }

    @Test
    void aSourceWhoseTextOnlyRepeatsItsTitleGivesOneSentenceNotAMadeUpSecond() {
        PublicEvidence bare = evidence("s-1", "Shell starts a new buyback programme", "Shell starts a new buyback programme", null);

        ClientReport built = FallbackReport.build(List.of(bare)).orElseThrow();

        assertThat(built.executiveSummary()).hasSize(1);
        assertThat(built.executiveSummary().getFirst().text()).isEqualTo("Shell starts a new buyback programme.");
    }

    @Test
    void takesTheSecondSentenceFromAnotherSourceWhenTheNewestHasNoUsableText() {
        PublicEvidence bare = evidence("s-1", "Shell starts a new buyback programme", "Menu Home News", "2026-11-04T00:00:00Z");
        PublicEvidence other = evidence("s-2", "Shell lifts its dividend - Reuters",
                "Shell raised its quarterly dividend by 4% on Thursday.", "2026-11-03T00:00:00Z");

        ClientReport built = FallbackReport.build(List.of(bare, other)).orElseThrow();

        assertThat(built.executiveSummary()).hasSize(2);
        assertThat(built.executiveSummary().get(1).text()).isEqualTo("Shell raised its quarterly dividend by 4% on Thursday.");
        assertThat(built.executiveSummary().get(1).sourceRefs()).containsExactly("s-2");
        assertThat(built.sourceRefs()).containsExactly("s-1", "s-2");
    }

    @Test
    void mentionsNoClientAndAVeryLongTitleAndSentenceAreShortened() {
        String longTitle = ("Apple " + "announces ".repeat(60) + "results").substring(0, 290);
        PublicEvidence long1 = evidence("s-1", longTitle, "Word " + "word ".repeat(200) + ".", null);

        ClientReport built = FallbackReport.build(List.of(long1)).orElseThrow();

        assertThat(built.headline().length()).isLessThanOrEqualTo(300);
        assertThat(built.executiveSummary()).allSatisfy(sentence -> assertThat(sentence.text().length()).isLessThanOrEqualTo(600));
        String everything = (built.headline() + built.executiveSummary()).toLowerCase();
        assertThat(everything).doesNotContain("client", "portfolio");
    }

    @Test
    void sourcesWithAdviceWordingGiveNoFallback() {
        PublicEvidence advice = evidence("s-1", "Analysts say consider buying Apple after earnings", "Consider buying the stock now.", null);

        assertThat(FallbackReport.build(List.of(advice))).isEmpty();
    }

    @Test
    void noSourcesMeansNoReport() {
        assertThat(FallbackReport.build(List.of())).isEmpty();
        assertThat(FallbackReport.build(null)).isEmpty();
    }

    @Test
    void cleansAPublisherSuffixOffATitleButOnlyAtTheEnd() {
        assertThat(FallbackReport.cleanTitle("Apple falls on its latest earnings report - CNBC")).isEqualTo("Apple falls on its latest earnings report");
        assertThat(FallbackReport.cleanTitle("Apple - the maker of the iPhone - reports results | Reuters")).isEqualTo("Apple - the maker of the iPhone - reports results");
        assertThat(FallbackReport.cleanTitle("Short - CNBC")).isEqualTo("Short - CNBC");
    }
}
