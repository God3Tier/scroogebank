package bank.clienthealth.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bank.clienthealth.analysis.application.RelationshipHealthAnalysisOrchestrator;
import bank.clienthealth.analysis.application.RelationshipHealthAnalysisOrchestrator.RunResult;
import bank.clienthealth.analysis.application.RelationshipHealthAnalysisOrchestrator.RunStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.DefaultApplicationArguments;

class AnalysisRunnerTest {
    private static final Instant NOW = Instant.parse("2026-10-27T16:30:00Z");
    private static final LocalDate SINGAPORE_DATE = LocalDate.of(2026, 10, 28);

    @ParameterizedTest
    @CsvSource({"COMPLETED, 0", "ALREADY_COMPLETED, 0", "LEASE_HELD, 1"})
    void usesTheSingaporeDateAndMapsRunStatusToExitCode(RunStatus status, int expectedExitCode) {
        RelationshipHealthAnalysisOrchestrator orchestrator =
                mock(RelationshipHealthAnalysisOrchestrator.class);
        when(orchestrator.run(SINGAPORE_DATE)).thenReturn(
                new RunResult("opaque-run", status, Map.of()));
        AnalysisRunner runner = new AnalysisRunner(
                orchestrator, Clock.fixed(NOW, ZoneOffset.UTC));

        runner.run(new DefaultApplicationArguments());

        verify(orchestrator).run(SINGAPORE_DATE);
        assertThat(runner.getExitCode()).isEqualTo(expectedExitCode);
    }

    @Test
    void exitsNonZeroWhenAnalysisFails() {
        RelationshipHealthAnalysisOrchestrator orchestrator =
                mock(RelationshipHealthAnalysisOrchestrator.class);
        when(orchestrator.run(SINGAPORE_DATE)).thenThrow(new IllegalStateException("synthetic failure"));
        AnalysisRunner runner = new AnalysisRunner(
                orchestrator, Clock.fixed(NOW, ZoneOffset.UTC));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isOne();
    }
}
