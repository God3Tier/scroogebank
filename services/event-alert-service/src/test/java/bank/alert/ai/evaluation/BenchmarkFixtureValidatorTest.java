package bank.alert.ai.evaluation;

import bank.alert.ai.EventType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkFixtureValidatorTest {
    @Test
    void committedFixtureIsSyntheticDiverseUniqueAndSafetyLabelled() throws Exception {
        Path fixturePath = locateFixture();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        BenchmarkFixtureFile fixture = mapper.readValue(fixturePath.toFile(), BenchmarkFixtureFile.class);

        FixtureValidationSummary summary = new BenchmarkFixtureValidator().validate(fixture);

        assertThat(fixture.labelStatus()).isEqualTo("AI_DRAFT_REQUIRES_HUMAN_REVIEW");
        assertThat(summary.examples()).isEqualTo(18);
        assertThat(summary.classifiable()).isEqualTo(13);
        assertThat(summary.negatives()).isEqualTo(5);
        assertThat(summary.coveredEventTypes()).containsExactlyInAnyOrder(EventType.values());
        assertThat(summary.coveredSafetyOutcomes()).containsExactlyInAnyOrder(
                "PROMPT_INJECTION_DETECTED", "PRIVATE_FIELD_DETECTED");
    }

    private static Path locateFixture() {
        List<Path> candidates = List.of(
                Path.of("tests/fixtures/event-driven-alerts/ai-evaluation.v1.json"),
                Path.of("../../tests/fixtures/event-driven-alerts/ai-evaluation.v1.json"));
        return candidates.stream().map(Path::toAbsolutePath).map(Path::normalize)
                .filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IllegalStateException("cannot locate committed AI evaluation fixture"));
    }
}
