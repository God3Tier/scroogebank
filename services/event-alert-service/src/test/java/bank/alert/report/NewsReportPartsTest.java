package bank.alert.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The summary sentence's checks and the combined PDF's layout (news-alert-drafts plan, sections 5.4 and 6). */
class NewsReportPartsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode report(String headline) throws Exception {
        return MAPPER.readTree("""
                {"headline":"%s","executiveSummary":[{"text":"Revenue rose to USD 94.9 billion, Reuters reported.","sourceRefs":["s1"]},
                 {"text":"Services grew.","sourceRefs":["s1"]}],
                 "keyFacts":[{"label":"Revenue","value":"USD 94.9 billion","context":"Fourth quarter.","sourceRefs":["s1"]}],
                 "whyItMatters":[{"text":"Shows demand.","kind":"FACT","sourceRefs":["s1"]}],
                 "marketContext":null,"whatToMonitor":["Guidance","Margins"],"uncertainty":"One source.","sourceRefs":["s1"]}
                """.formatted(headline));
    }

    @Test
    void aSummarySentenceMustNameTheIssuerUseTheReportsFiguresAndGiveNoAdvice() throws Exception {
        JsonNode report = report("Apple reports record revenue");
        assertThat(SummarySentenceGenerator.check("Apple reported revenue of USD 94.9 billion as services grew.", report,
                "Apple Inc", "AAPL")).isNull();
        assertThat(SummarySentenceGenerator.check("Apple, as Bloomberg noted, reported record revenue.", report,
                "Apple Inc", "AAPL")).as("naming a news source is fine").isNull();
        assertThat(SummarySentenceGenerator.check("Apple reported revenue of USD 99 billion.", report, "Apple Inc", "AAPL"))
                .isEqualTo("UNSUPPORTED_FIGURE");
        assertThat(SummarySentenceGenerator.check("Revenue rose sharply.", report, "Apple Inc", "AAPL"))
                .isEqualTo("ISSUER_NOT_NAMED");
        assertThat(SummarySentenceGenerator.check("Apple grew. Services rose.", report, "Apple Inc", "AAPL"))
                .isEqualTo("MORE_THAN_ONE_SENTENCE");
        assertThat(SummarySentenceGenerator.check("Apple grew [1].", report, "Apple Inc", "AAPL"))
                .isEqualTo("CITATION_MARKER");
        assertThat(SummarySentenceGenerator.check("Apple grew, so you should buy.", report, "Apple Inc", "AAPL"))
                .isEqualTo("ADVICE_LANGUAGE_DETECTED");
        assertThat(SummarySentenceGenerator.check("x".repeat(251), report, "Apple Inc", "AAPL"))
                .isEqualTo("SENTENCE_TOO_LONG");
    }

    @Test
    void theCombinedPdfStartsEachCompanyOnItsOwnPageAfterTheCoverAndContents() throws Exception {
        List<ReportPdfRenderer.Section> sections = new ArrayList<>();
        for (String issuer : List.of("Apple Inc", "Shell plc", "Anthropic")) {
            sections.add(new ReportPdfRenderer.Section(issuer, issuer.substring(0, 3).toUpperCase(),
                    "AI-drafted from public sources and reviewed by your relationship manager.", report(issuer + " update"),
                    List.of(new ReportPdfRenderer.SourceLine("s1", "A source — with 中 punctuation", "example.com",
                            Instant.parse("2026-11-03T01:00:00Z"), "https://example.com/" + "a".repeat(150)))));
        }
        var renderer = new ReportPdfRenderer();
        var document = new ReportPdfRenderer.Document(LocalDate.of(2026, 11, 7), "Alex Tan", sections);

        var rendered = renderer.render(document);

        assertThat(rendered.startPages()).containsExactly(3, 4, 5);
        assertThat(rendered.pageCount()).as("cover, contents, three companies, closing").isEqualTo(6);
        assertThat(renderer.render(document).pdf()).as("the same input gives the same bytes").isEqualTo(rendered.pdf());
        assertThat(ReportPdfRenderer.fileName(LocalDate.of(2026, 11, 7))).isEqualTo("Portfolio-update-2026-11-07.pdf");
        assertThat(ReportPdfRenderer.clean("A 中 B C")).as("text the standard fonts lack is replaced").isEqualTo("A ? B C");
    }

    @Test
    void theEmailLineIsTheAiSentenceOnlyForAnUneditedReport() throws Exception {
        JsonNode report = report("Apple reports");
        assertThat(ReportReviewService.summaryLine(false, "Apple grew.", report)).isEqualTo("Apple grew.");
        assertThat(ReportReviewService.summaryLine(true, "Apple grew.", report))
                .isEqualTo("Revenue rose to USD 94.9 billion, Reuters reported.");
        assertThat(ReportReviewService.summaryLine(false, null, report))
                .isEqualTo("Revenue rose to USD 94.9 billion, Reuters reported.");
    }
}
