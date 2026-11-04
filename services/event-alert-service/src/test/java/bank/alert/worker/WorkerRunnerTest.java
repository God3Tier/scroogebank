package bank.alert.worker;

import bank.alert.config.ReplayProperties;
import bank.alert.config.ResearchProperties;
import bank.alert.ingestion.IngestionJob;
import bank.alert.ingestion.IngestionOutcome;
import bank.alert.ingestion.IngestionStatus;
import bank.alert.operations.OperationalControlAuditUnavailableException;
import bank.alert.operations.OutboundControlService;
import bank.alert.processing.PipelineSummary;
import bank.alert.processing.SourcePipeline;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-14T04:00:30Z");
    private static final ReplayProperties NO_REPLAY = new ReplayProperties(null, null, null, null);
    private static final ReplayProperties REPLAY = new ReplayProperties(
            "2026-10-14T01:00:00Z", "2026-10-14T03:00:00Z", "operator-1", "corr-1");

    private final IngestionJob job = mock(IngestionJob.class);
    private final ReplayRequestAudit audit = mock(ReplayRequestAudit.class);
    private final OutboundControlService controls = mock(OutboundControlService.class);
    private final SourcePipeline pipeline = mock(SourcePipeline.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void quietPipeline() {
        when(pipeline.processPending()).thenReturn(new PipelineSummary(0, 0, 0, 0, 0, 0, null));
    }

    private WorkerRunner runner(ReplayProperties replay) {
        return new WorkerRunner(job, Clock.fixed(NOW, ZoneOffset.UTC), replay, audit,
                new ResearchProperties(null, null, null, null), meters, controls, pipeline);
    }

    @ParameterizedTest
    @EnumSource(IngestionStatus.class)
    void runsTheWindowEndingNowAndExitsNonZeroOnlyWhenItFailed(IngestionStatus status) {
        when(job.runScheduledWindow(NOW)).thenReturn(IngestionOutcome.skipped(status, NOW));
        WorkerRunner runner = runner(NO_REPLAY);

        runner.run(new DefaultApplicationArguments());

        verify(job).runScheduledWindow(NOW);
        verify(audit, never()).record(any(), any());
        assertThat(runner.getExitCode()).isEqualTo(status == IngestionStatus.FAILED ? 1 : 0);
        assertThat(meters.counter("alert.worker.runs", "status", status.name().toLowerCase()).count())
                .isEqualTo(1.0);
    }

    @Test
    void reportsResultCountsAsMetrics() {
        when(job.runScheduledWindow(NOW)).thenReturn(new IngestionOutcome(IngestionStatus.SUCCEEDED,
                UUID.randomUUID(), NOW.minusSeconds(3600), NOW, 4, 7, 2, 1, null));

        runner(NO_REPLAY).run(new DefaultApplicationArguments());

        assertThat(meters.counter("alert.worker.results", "outcome", "accepted").count()).isEqualTo(7.0);
        assertThat(meters.counter("alert.worker.results", "outcome", "duplicate").count()).isEqualTo(2.0);
        assertThat(meters.counter("alert.worker.results", "outcome", "rejected").count()).isEqualTo(1.0);
        assertThat(meters.counter("alert.worker.search.attempts").count()).isEqualTo(4.0);
        verify(controls).publishUsageMetrics();
    }

    @Test
    void aMetricsFailureNeverChangesTheRunResult() {
        when(job.runScheduledWindow(NOW)).thenReturn(IngestionOutcome.skipped(IngestionStatus.SUCCEEDED, NOW));
        doThrow(new IllegalStateException("db down")).when(controls).publishUsageMetrics();
        WorkerRunner runner = runner(NO_REPLAY);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void replaysTheNamedWindowAfterWritingTheAuditEvent() {
        when(job.replayWindow(REPLAY.windowFrom(), REPLAY.windowTo()))
                .thenReturn(IngestionOutcome.skipped(IngestionStatus.SUCCEEDED, REPLAY.windowTo()));
        WorkerRunner runner = runner(REPLAY);

        runner.run(new DefaultApplicationArguments());

        var order = org.mockito.Mockito.inOrder(audit, job);
        order.verify(audit).record(REPLAY, NOW);
        order.verify(job).replayWindow(REPLAY.windowFrom(), REPLAY.windowTo());
        verify(job, never()).runScheduledWindow(any());
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void aReplayWithoutAnOperatorIsRefusedBeforeAnySearch() {
        ReplayProperties noOperator = new ReplayProperties(
                "2026-10-14T01:00:00Z", "2026-10-14T03:00:00Z", null, "corr-1");
        WorkerRunner runner = runner(noOperator);

        runner.run(new DefaultApplicationArguments());

        verify(audit, never()).record(any(), any());
        verify(job, never()).replayWindow(any(), any());
        verify(job, never()).runScheduledWindow(any());
        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(meters.counter("alert.worker.runs", "status", "replay_refused").count()).isEqualTo(1.0);
    }

    @Test
    void aReplayIsRefusedWhenItsAuditEventCannotBeStored() {
        doThrow(new OperationalControlAuditUnavailableException("audit outbox is at capacity"))
                .when(audit).record(any(), any());
        WorkerRunner runner = runner(REPLAY);

        runner.run(new DefaultApplicationArguments());

        verify(job, never()).replayWindow(any(), any());
        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void anInvalidReplayWindowFromTheCoordinatorFailsTheRunWithoutSearching() {
        when(job.replayWindow(any(), any())).thenThrow(
                new IllegalArgumentException("replay window cannot extend into the future"));
        WorkerRunner runner = runner(REPLAY);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void turnsTheStoredSourcesIntoAlertsAfterTheWindowAndReportsTheCounts() {
        when(job.runScheduledWindow(NOW)).thenReturn(IngestionOutcome.skipped(IngestionStatus.SUCCEEDED, NOW));
        when(pipeline.processPending()).thenReturn(new PipelineSummary(5, 2, 3, 6, 0, 0, null));
        WorkerRunner runner = runner(NO_REPLAY);

        runner.run(new DefaultApplicationArguments());

        var order = org.mockito.Mockito.inOrder(job, pipeline);
        order.verify(job).runScheduledWindow(NOW);
        order.verify(pipeline).processPending();
        assertThat(meters.counter("alert.worker.alerts.created").count()).isEqualTo(6.0);
        assertThat(meters.counter("alert.worker.pipeline.sources", "outcome", "no_match").count()).isEqualTo(2.0);
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void aDeferredSourceIsNotAFailure() {
        when(job.runScheduledWindow(NOW)).thenReturn(IngestionOutcome.skipped(IngestionStatus.DISABLED, NOW));
        when(pipeline.processPending()).thenReturn(new PipelineSummary(1, 0, 0, 0, 1, 0, null));
        WorkerRunner runner = runner(NO_REPLAY);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void aSourceThatErroredFailsTheRunSoTheAlarmFires() {
        when(job.runScheduledWindow(NOW)).thenReturn(IngestionOutcome.skipped(IngestionStatus.SUCCEEDED, NOW));
        when(pipeline.processPending()).thenReturn(new PipelineSummary(2, 0, 1, 1, 0, 1, null));
        WorkerRunner runner = runner(NO_REPLAY);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void aCrashedPipelineFailsTheRunWithoutHidingTheIngestionResult() {
        when(job.runScheduledWindow(NOW)).thenReturn(IngestionOutcome.skipped(IngestionStatus.SUCCEEDED, NOW));
        when(pipeline.processPending()).thenThrow(new IllegalStateException("db down"));
        WorkerRunner runner = runner(NO_REPLAY);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(meters.counter("alert.worker.pipeline.failures").count()).isEqualTo(1.0);
        assertThat(meters.counter("alert.worker.runs", "status", "succeeded").count()).isEqualTo(1.0);
    }

    @Test
    void aRefusedReplayDoesNotStartThePipeline() {
        ReplayProperties noOperator = new ReplayProperties(
                "2026-10-14T01:00:00Z", "2026-10-14T03:00:00Z", null, "corr-1");

        runner(noOperator).run(new DefaultApplicationArguments());

        verify(pipeline, never()).processPending();
    }
}
