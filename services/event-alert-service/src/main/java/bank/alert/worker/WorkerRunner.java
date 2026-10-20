package bank.alert.worker;

import bank.alert.config.ReplayProperties;
import bank.alert.ingestion.IngestionJob;
import bank.alert.ingestion.IngestionOutcome;
import bank.alert.ingestion.IngestionStatus;
import bank.alert.operations.OperationalControlAuditUnavailableException;
import bank.alert.operations.OutboundControlService;
import bank.alert.processing.PipelineSummary;
import bank.alert.processing.SourcePipeline;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Runs one ingestion window when the service starts in WORKER mode. EventBridge
 * Scheduler starts the worker task every 4 hours (ADR-0031 decision 1); the
 * process then exits, with code 1 if the window failed.
 *
 * An operator can instead start a one-off task with the replay variables set
 * (ADR-0049): the worker then replays that historical window, after writing a
 * replay audit event, and never moves the scheduler watermark.
 */
@Component
@ConditionalOnProperty(prefix = "alert", name = "mode", havingValue = "WORKER")
public class WorkerRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);
    private static final String SUMMARY = "worker run status={} run_id={} window_from={} window_to={} "
            + "search_attempts={} accepted={} duplicates={} rejected={} error_code={}";

    private final IngestionJob job;
    private final Clock clock;
    private final ReplayProperties replay;
    private final ReplayRequestAudit replayAudit;
    private final MeterRegistry meters;
    private final OutboundControlService controls;
    private final SourcePipeline pipeline;
    private int exitCode;

    public WorkerRunner(IngestionJob job, Clock clock, ReplayProperties replay,
            ReplayRequestAudit replayAudit, MeterRegistry meters, OutboundControlService controls,
            SourcePipeline pipeline) {
        this.job = job;
        this.clock = clock;
        this.replay = replay;
        this.replayAudit = replayAudit;
        this.meters = meters;
        this.controls = controls;
        this.pipeline = pipeline;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            IngestionOutcome outcome = replay.requested() ? replayWindow() : scheduledWindow();
            if (outcome != null) {
                report(outcome);
                runPipeline();
            }
        } finally {
            // The worker exits within seconds, so refresh the shared monthly usage
            // gauges now; the registry exports its last values as the context closes.
            publishUsage();
        }
    }

    private IngestionOutcome scheduledWindow() {
        return job.runScheduledWindow(clock.instant());
    }

    private IngestionOutcome replayWindow() {
        try {
            replay.validate();
            replayAudit.record(replay, clock.instant());
        } catch (IllegalArgumentException | OperationalControlAuditUnavailableException refused) {
            exitCode = 1;
            String code = refused instanceof IllegalArgumentException
                    ? "INVALID_REPLAY_REQUEST" : "REPLAY_AUDIT_UNAVAILABLE";
            meters.counter("alert.worker.runs", "status", "replay_refused").increment();
            log.error("worker replay refused error_code={}", code);
            return null;
        }
        log.info("worker replay requested correlation_id={}", replay.correlationId());
        try {
            return job.replayWindow(replay.windowFrom(), replay.windowTo());
        } catch (IllegalArgumentException invalidWindow) {
            exitCode = 1;
            meters.counter("alert.worker.runs", "status", "replay_refused").increment();
            log.error("worker replay refused error_code=INVALID_REPLAY_WINDOW");
            return null;
        }
    }

    private void report(IngestionOutcome outcome) {
        // Counts and codes only: never search terms, titles or URLs.
        Object[] fields = {outcome.status(), outcome.runId(), outcome.windowFrom(), outcome.windowTo(),
                outcome.searchAttempts(), outcome.acceptedResults(), outcome.duplicateResults(),
                outcome.rejectedResults(), outcome.errorCode()};
        if (outcome.status() == IngestionStatus.FAILED) {
            exitCode = 1;
            log.error(SUMMARY, fields);
        } else {
            log.info(SUMMARY, fields);
        }
        meters.counter("alert.worker.runs", "status", outcome.status().name().toLowerCase()).increment();
        meters.counter("alert.worker.results", "outcome", "accepted").increment(outcome.acceptedResults());
        meters.counter("alert.worker.results", "outcome", "duplicate").increment(outcome.duplicateResults());
        meters.counter("alert.worker.results", "outcome", "rejected").increment(outcome.rejectedResults());
        meters.counter("alert.worker.search.attempts").increment(outcome.searchAttempts());
    }

    /** Turns the sources stored so far, this run's and any earlier ones left pending, into alerts. */
    private void runPipeline() {
        PipelineSummary summary;
        try {
            summary = pipeline.processPending();
        } catch (RuntimeException failure) {
            exitCode = 1;
            meters.counter("alert.worker.pipeline.failures").increment();
            log.error("worker pipeline failed error_code={}", failure.getClass().getSimpleName());
            return;
        }
        log.info("worker pipeline examined={} processed={} no_match={} alerts_created={} deferred={} "
                        + "errors={} skipped={}", summary.examined(), summary.processed(), summary.noMatch(),
                summary.alertsCreated(), summary.deferred(), summary.errors(), summary.skippedReason());
        if (summary.errors() > 0) exitCode = 1;
        meters.counter("alert.worker.pipeline.sources", "outcome", "processed").increment(summary.processed());
        meters.counter("alert.worker.pipeline.sources", "outcome", "no_match").increment(summary.noMatch());
        meters.counter("alert.worker.pipeline.sources", "outcome", "deferred").increment(summary.deferred());
        meters.counter("alert.worker.pipeline.sources", "outcome", "error").increment(summary.errors());
        meters.counter("alert.worker.alerts.created").increment(summary.alertsCreated());
    }

    private void publishUsage() {
        try {
            controls.publishUsageMetrics();
        } catch (RuntimeException unavailable) {
            // A metrics read must never change the run's result.
            log.warn("worker usage metrics unavailable");
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
