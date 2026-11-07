package bank.alert.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A report with fewer parts (one sentence, or built from the sources alone) can still be edited; a full one keeps its minimums. */
class ReportEditsShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, ReportEdits.SourceText> SOURCES = Map.of(
            "s-1", new ReportEdits.SourceText("Apple falls on its latest earnings report", "Apple shares fell 2% in extended trading."));

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static final String FULL = """
            {"headline":"Apple falls","executiveSummary":[{"text":"Apple fell 2%.","sourceRefs":["s-1"]},{"text":"It missed estimates.","sourceRefs":["s-1"]}],
             "keyFacts":[],"whyItMatters":[{"text":"It may matter.","kind":"POTENTIAL_IMPLICATION","sourceRefs":[]}],"marketContext":null,
             "whatToMonitor":["Guidance","Margins"],"uncertainty":"Only one source.","sourceRefs":["s-1"]}""";

    private static final String FROM_SOURCES_ONLY = """
            {"headline":"Apple falls on its latest earnings report","executiveSummary":[{"text":"Apple falls on its latest earnings report.","sourceRefs":["s-1"]}],
             "keyFacts":[],"whyItMatters":[],"marketContext":null,"whatToMonitor":[],"uncertainty":"","sourceRefs":["s-1"]}""";

    @Test
    void aReportBuiltFromTheSourcesAloneCanBeEditedAndKeepsItsEmptySections() throws Exception {
        JsonNode original = json(FROM_SOURCES_ONLY);
        JsonNode sections = json("""
                {"executiveSummary":["Apple fell 2% after its latest earnings report."],"keyFacts":[],"whyItMatters":[],
                 "whatToMonitor":[],"uncertainty":""}""");

        JsonNode merged = ReportEdits.merge(original, sections);

        assertThat(merged.path("executiveSummary").get(0).path("text").asText()).isEqualTo("Apple fell 2% after its latest earnings report.");
        assertThat(merged.path("whatToMonitor")).isEmpty();
        assertThat(ReportEdits.check(original, merged, SOURCES)).isNull();
    }

    @Test
    void aFullReportStillNeedsTwoOrThreeSentencesAndTwoToFourThingsToMonitor() throws Exception {
        JsonNode original = json(FULL);

        assertThatThrownBy(() -> ReportEdits.merge(original, json("""
                {"executiveSummary":["One.","Two."],"keyFacts":[],"whyItMatters":["Matters."],"whatToMonitor":["Only one"],"uncertainty":"u"}""")))
                .isInstanceOf(ReportEdits.EditRefused.class);

        ((com.fasterxml.jackson.databind.node.ObjectNode) original).putArray("executiveSummary")
                .addObject().put("text", "Only one sentence.").putArray("sourceRefs").add("s-1");
        // The original had one sentence, so one is accepted; a full report (two) would not be.
        JsonNode oneSentence = original;
        assertThat(ReportEdits.check(oneSentence, oneSentence, SOURCES)).isNull();
        assertThat(ReportEdits.check(json(FULL), editedWithSummary(json(FULL), 1), SOURCES))
                .isNotNull().extracting(ReportEdits.Violation::code).isEqualTo("INVALID_SHAPE");
    }

    private static JsonNode editedWithSummary(JsonNode report, int sentences) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = report.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode summary = copy.putArray("executiveSummary");
        for (int i = 0; i < sentences; i++) summary.addObject().put("text", "Sentence " + i + ".").putArray("sourceRefs").add("s-1");
        return copy;
    }

    @Test
    void aNormalReportCannotDropItsUncertaintyNote() throws Exception {
        JsonNode original = json(FULL);
        com.fasterxml.jackson.databind.node.ObjectNode edited = original.deepCopy();
        edited.put("uncertainty", "");

        assertThat(ReportEdits.check(original, edited, SOURCES)).isNotNull().extracting(ReportEdits.Violation::code).isEqualTo("EMPTY_TEXT");
    }
}
