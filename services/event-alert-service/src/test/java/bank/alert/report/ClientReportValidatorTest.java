package bank.alert.report;

import bank.alert.ai.PublicEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientReportValidatorTest {

    private static final Instant AT = Instant.parse("2026-11-02T04:00:00Z");
    private static final PublicEvidence A = new PublicEvidence("src-a", "Microsoft announces first quarter results",
            "Revenue was $92,279 million, up 19%. Operating income was $45,915 million.",
            "https://news.example/a", "news.example", AT, List.of("NASDAQ-MSFT"));
    private static final PublicEvidence B = new PublicEvidence("src-b", "Microsoft Q1 earnings report",
            "Analysts expected $32.32 billion for Azure and AI services.",
            "https://other.example/b", "other.example", AT, List.of("NASDAQ-MSFT"));
    private static final List<PublicEvidence> EVIDENCE = List.of(A, B);

    private static ClientReport.CitedText sentence(String text, String... refs) {
        return new ClientReport.CitedText(text, List.of(refs));
    }

    private static ClientReportModelOutput output(List<ClientReport.KeyFact> facts, List<ClientReport.Implication> why,
            String marketContext, List<String> monitor, List<ClientReport.CitedText> summary) {
        return new ClientReportModelOutput(false, null, "Microsoft reports first quarter results", summary, facts, why,
                marketContext, monitor, "Only one excerpt was supplied.", List.of("src-a", "src-b"));
    }

    private static List<ClientReport.CitedText> summary() {
        return List.of(sentence("Microsoft reported quarterly revenue growth.", "src-a"),
                sentence("Analysts had expected strong Azure growth.", "https://other.example/b"));
    }

    private static List<ClientReport.Implication> why() {
        return List.of(new ClientReport.Implication("Revenue grew year on year.", ClientReport.Kind.FACT, List.of("src-a")),
                new ClientReport.Implication("Cloud demand may support further growth.", ClientReport.Kind.POTENTIAL_IMPLICATION, List.of()));
    }

    private static List<String> monitor() {
        return List.of("Azure growth in the next quarter", "Guidance on capital spending");
    }

    private static List<ClientReport.KeyFact> facts() {
        return List.of(new ClientReport.KeyFact("Revenue", "$92,279 million", "Up 19% on a year earlier", List.of("src-a")));
    }

    private String check(ClientReportModelOutput output) {
        return ClientReportValidator.validate(output, EVIDENCE);
    }

    @Test
    void aWellFormedCitedReportIsAccepted() {
        assertThat(check(output(facts(), why(), null, monitor(), summary()))).isNull();
    }

    @Test
    void aOneSentenceSummaryIsRefusedUnlessTheCheckIsRelaxed() {
        List<ClientReport.CitedText> one = List.of(sentence("Microsoft reported quarterly revenue growth.", "src-a"));
        ClientReportModelOutput output = output(facts(), why(), null, monitor(), one);

        assertThat(check(output)).isEqualTo("INVALID_MODEL_SCHEMA");
        assertThat(ClientReportValidator.validate(output, EVIDENCE, true)).isNull();
    }

    @Test
    void relaxingStillRefusesAnEmptySummaryAndTooManySentences() {
        List<ClientReport.CitedText> four = List.of(sentence("One.", "src-a"), sentence("Two.", "src-a"),
                sentence("Three.", "src-a"), sentence("Four.", "src-a"));

        assertThat(ClientReportValidator.validate(output(facts(), why(), null, monitor(), List.of()), EVIDENCE, true))
                .isEqualTo("INVALID_MODEL_SCHEMA");
        assertThat(ClientReportValidator.validate(output(facts(), why(), null, monitor(), four), EVIDENCE, true))
                .isEqualTo("INVALID_MODEL_SCHEMA");
    }

    @Test
    void aSourceMayBeCitedByItsUrlAndIsStoredByItsId() {
        ClientReportModelOutput output = output(facts(), why(), null, monitor(), summary());

        ClientReport report = ClientReportValidator.accepted(output, EVIDENCE);

        assertThat(report.executiveSummary().get(1).sourceRefs()).containsExactly("src-b");
        assertThat(report.marketContext()).isNull();
        assertThat(report.keyFacts()).singleElement().satisfies(fact -> assertThat(fact.sourceRefs()).containsExactly("src-a"));
    }

    @Test
    void everySummarySentenceMustCiteASuppliedSource() {
        List<ClientReport.CitedText> uncited = List.of(sentence("Revenue grew.", "src-a"), sentence("Growth continued."));
        List<ClientReport.CitedText> unknown = List.of(sentence("Revenue grew.", "src-a"), sentence("Growth continued.", "src-zzz"));

        assertThat(check(output(facts(), why(), null, monitor(), uncited))).isEqualTo("MISSING_OR_UNKNOWN_CITATION");
        assertThat(check(output(facts(), why(), null, monitor(), unknown))).isEqualTo("MISSING_OR_UNKNOWN_CITATION");
    }

    @Test
    void aFactualWhyItMattersItemMustCiteButAPotentialImplicationNeedNot() {
        List<ClientReport.Implication> uncitedFact = List.of(
                new ClientReport.Implication("Revenue grew.", ClientReport.Kind.FACT, List.of()));

        assertThat(check(output(facts(), uncitedFact, null, monitor(), summary()))).isEqualTo("MISSING_OR_UNKNOWN_CITATION");
        assertThat(check(output(facts(), why(), null, monitor(), summary()))).isNull();
    }

    @Test
    void aPotentialImplicationStillMayNotCiteAnUnknownSource() {
        List<ClientReport.Implication> bad = List.of(
                new ClientReport.Implication("It may help.", ClientReport.Kind.POTENTIAL_IMPLICATION, List.of("src-zzz")));

        assertThat(check(output(facts(), bad, null, monitor(), summary()))).isEqualTo("MISSING_OR_UNKNOWN_CITATION");
    }

    @Test
    void everyFigureInAKeyFactMustAppearInACitedSourceAfterDroppingThousandsSeparators() {
        List<ClientReport.KeyFact> invented = List.of(
                new ClientReport.KeyFact("Revenue", "$99,999 million", "Up 19%", List.of("src-a")));
        List<ClientReport.KeyFact> wrongSource = List.of(
                new ClientReport.KeyFact("Azure", "$32.32 billion", "Analyst expectation", List.of("src-a")));
        List<ClientReport.KeyFact> fromTheRightSource = List.of(
                new ClientReport.KeyFact("Azure", "$32.32 billion", "Analyst expectation", List.of("src-b")));
        List<ClientReport.KeyFact> noFigures = List.of(
                new ClientReport.KeyFact("Event", "First quarter results", "Published on the company's site", List.of("src-a")));

        assertThat(check(output(invented, why(), null, monitor(), summary()))).isEqualTo("UNSUPPORTED_FIGURE");
        assertThat(check(output(wrongSource, why(), null, monitor(), summary()))).isEqualTo("UNSUPPORTED_FIGURE");
        assertThat(check(output(fromTheRightSource, why(), null, monitor(), summary()))).isNull();
        assertThat(check(output(noFigures, why(), null, monitor(), summary()))).isNull();
    }

    @Test
    void aFigureMustMatchWholeNotAPartOfAnotherNumber() {
        List<ClientReport.KeyFact> part = List.of(
                new ClientReport.KeyFact("Revenue", "$9,227 million", "Part of 92,279", List.of("src-a")));

        assertThat(check(output(part, why(), null, monitor(), summary()))).isEqualTo("UNSUPPORTED_FIGURE");
    }

    @Test
    void marketContextIsNeverAllowedWithoutAFeed() {
        assertThat(check(output(facts(), why(), "Shares closed at $420.", monitor(), summary())))
                .isEqualTo("MARKET_CONTEXT_NOT_ALLOWED");
    }

    @Test
    void adviceLanguageAnywhereRefusesTheReport() {
        List<ClientReport.Implication> advice = List.of(
                new ClientReport.Implication("Investors should buy before the next quarter.", ClientReport.Kind.FACT, List.of("src-a")));
        List<String> adviceMonitor = List.of("We recommend selling ahead of guidance", "Guidance on capital spending");

        assertThat(check(output(facts(), advice, null, monitor(), summary()))).isEqualTo("ADVICE_LANGUAGE_DETECTED");
        assertThat(check(output(facts(), why(), null, adviceMonitor, summary()))).isEqualTo("ADVICE_LANGUAGE_DETECTED");
    }

    @Test
    void theShapeIsEnforced() {
        List<ClientReport.CitedText> one = List.of(sentence("Only one sentence.", "src-a"));
        List<ClientReport.CitedText> four = List.of(sentence("a.", "src-a"), sentence("b.", "src-a"), sentence("c.", "src-a"), sentence("d.", "src-a"));
        List<ClientReport.KeyFact> five = java.util.Collections.nCopies(5,
                new ClientReport.KeyFact("Event", "Results", "Context", List.of("src-a")));

        assertThat(check(output(facts(), why(), null, monitor(), one))).isEqualTo("INVALID_MODEL_SCHEMA");
        assertThat(check(output(facts(), why(), null, monitor(), four))).isEqualTo("INVALID_MODEL_SCHEMA");
        assertThat(check(output(five, why(), null, monitor(), summary()))).isEqualTo("INVALID_MODEL_SCHEMA");
        assertThat(check(output(facts(), why(), null, List.of("Only one thing"), summary()))).isEqualTo("INVALID_MODEL_SCHEMA");
        assertThat(check(output(facts(), List.of(), null, monitor(), summary()))).isEqualTo("INVALID_MODEL_SCHEMA");
    }

    @Test
    void anAbstentionNeedsAReason() {
        ClientReportModelOutput withReason = new ClientReportModelOutput(true, "Evidence too thin", null, null, null, null,
                null, null, null, null);
        ClientReportModelOutput without = new ClientReportModelOutput(true, " ", null, null, null, null, null, null, null, null);
        ClientReportModelOutput missingFlag = new ClientReportModelOutput(null, null, null, null, null, null, null, null, null, null);

        assertThat(check(withReason)).isNull();
        assertThat(check(without)).isEqualTo("INVALID_ABSTENTION");
        assertThat(check(missingFlag)).isEqualTo("INVALID_MODEL_SCHEMA");
    }

    @Test
    void wrappingQuotesAroundAModelsTextAreNotPartOfTheText() {
        assertThat(ClientReportValidator.tidy("=\"Only one source was supplied.\"")).isEqualTo("Only one source was supplied.");
        assertThat(ClientReportValidator.tidy("  \"Quoted headline\" ")).isEqualTo("Quoted headline");
        assertThat(ClientReportValidator.tidy("Revenue rose \"strongly\" this year")).isEqualTo("Revenue rose \"strongly\" this year");
        assertThat(ClientReportValidator.tidy("=")).isEqualTo("=");
    }

    @Test
    void numbersAreReadWithoutTheirSeparators() {
        assertThat(ClientReportValidator.numbers("$92,279 million, up 19% and 3.5x")).containsExactly("92279", "19", "3.5");
    }
}
