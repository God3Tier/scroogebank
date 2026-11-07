package bank.alert.ai.evaluation;

import bank.alert.ai.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiBenchmarkReportCliTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsVersionedFixtureAndRunFilesIntoAnAuditableReport() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path fixturePath = temporaryDirectory.resolve("fixture.json");
        Path runPath = temporaryDirectory.resolve("run.json");
        mapper.writeValue(fixturePath.toFile(), new BenchmarkFixtureFile(
                "fixture-v1", "synthetic-public-only", "HUMAN_REVIEWED", List.of(
                new BenchmarkFixtureFile.FixtureExample("one", "Synthetic earnings", "Public excerpt",
                        List.of("SYN-1"), EventType.EARNINGS, true, null),
                new BenchmarkFixtureFile.FixtureExample("two", "Community event", "Public excerpt",
                        List.of("SYN-2"), null, false, null))));
        mapper.writeValue(runPath.toFile(), new BenchmarkRunFile(
                "global.amazon.nova-2-lite-v1:0", "alert-public-v1", new BigDecimal("0.0012"), List.of(
                observation("one", EventType.EARNINGS, true, 125),
                observation("two", null, false, 250))));

        VersionedBenchmarkReport result = new AiBenchmarkReportCli(mapper).generate(fixturePath, runPath);

        assertThat(result.fixtureVersion()).isEqualTo("fixture-v1");
        assertThat(result.model()).isEqualTo("global.amazon.nova-2-lite-v1:0");
        assertThat(result.promptVersion()).isEqualTo("alert-public-v1");
        assertThat(result.fixtureLabelStatus()).isEqualTo("HUMAN_REVIEWED");
        assertThat(result.estimatedCostUsd()).isEqualByComparingTo("0.0012");
        assertThat(result.eligibleForEnablement()).isTrue();
        assertThat(result.report().passed()).isTrue();
        assertThat(result.report().p95LatencyMillis()).isEqualTo(250);
    }

    @Test
    void draftFixtureCanBeMeasuredButCannotBecomeEnablementEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path fixturePath = temporaryDirectory.resolve("draft-fixture.json");
        Path runPath = temporaryDirectory.resolve("draft-run.json");
        mapper.writeValue(fixturePath.toFile(), new BenchmarkFixtureFile(
                "fixture-v1", "synthetic-public-only", "AI_DRAFT_REQUIRES_HUMAN_REVIEW", List.of(
                new BenchmarkFixtureFile.FixtureExample("one", "Synthetic earnings", "Public excerpt",
                        List.of("SYN-1"), EventType.EARNINGS, true, null))));
        mapper.writeValue(runPath.toFile(), new BenchmarkRunFile(
                "global.amazon.nova-2-lite-v1:0", "alert-public-v1", new BigDecimal("0.0006"),
                List.of(observation("one", EventType.EARNINGS, true, 125))));

        VersionedBenchmarkReport result = new AiBenchmarkReportCli(mapper).generate(fixturePath, runPath);

        assertThat(result.report().passed()).isTrue();
        assertThat(result.eligibleForEnablement()).isFalse();
    }

    private static BenchmarkObservation observation(String id, EventType eventType,
            boolean accepted, long latency) {
        return new BenchmarkObservation(id, eventType, accepted, true, true, true,
                true, "end_turn", 20, 10, latency);
    }
}
