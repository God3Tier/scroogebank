package bank.alert.worker;

import bank.alert.ingestion.IngestionJob;
import bank.alert.ingestion.IngestionOutcome;
import bank.alert.ingestion.IngestionStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-14T04:00:30Z");

    @ParameterizedTest
    @EnumSource(IngestionStatus.class)
    void runsTheWindowEndingNowAndExitsNonZeroOnlyWhenItFailed(IngestionStatus status) {
        IngestionJob job = mock(IngestionJob.class);
        when(job.runScheduledWindow(NOW)).thenReturn(IngestionOutcome.skipped(status, NOW));
        WorkerRunner runner = new WorkerRunner(job, Clock.fixed(NOW, ZoneOffset.UTC));

        runner.run(new DefaultApplicationArguments());

        verify(job).runScheduledWindow(NOW);
        assertThat(runner.getExitCode()).isEqualTo(status == IngestionStatus.FAILED ? 1 : 0);
    }
}
