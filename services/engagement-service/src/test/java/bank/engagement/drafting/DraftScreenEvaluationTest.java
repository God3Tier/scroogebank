package bank.engagement.drafting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the hand-written evaluation cases through the safety screen, offline
 * (following event-alert-service's ai/evaluation fixtures). Every case must
 * get its expected verdict.
 */
class DraftScreenEvaluationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DraftSafetyScreen screen = new DraftSafetyScreen(mapper);

    @Test
    void everyCaseGetsItsExpectedVerdict() throws Exception {
        JsonNode fixture;
        try (InputStream in = getClass().getResourceAsStream("/ai-evaluation/draft-screen-cases.json")) {
            fixture = mapper.readTree(in);
        }
        assertThat(fixture.path("labelStatus").asText()).isEqualTo("SYNTHETIC");
        assertThat(fixture.path("cases")).hasSizeGreaterThanOrEqualTo(10);

        for (JsonNode testCase : fixture.path("cases")) {
            // A case names its purpose when the purpose changes the verdict (ADR-0068's details slot).
            DraftPurpose purpose = DraftPurpose.valueOf(testCase.path("purpose").asText("CHECK_IN"));
            DraftSafetyScreen.Result result = screen.inspect(testCase.path("output").asText(), purpose);
            String verdict = result.accepted() ? "accepted" : result.reason();
            assertThat(verdict).as(testCase.path("name").asText()).isEqualTo(testCase.path("expected").asText());
        }
    }
}
