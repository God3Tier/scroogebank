package bank.alert.worker;

import bank.alert.ingestion.IngestionJob;
import bank.alert.ingestion.IngestionOutcome;
import bank.alert.ingestion.IngestionStatus;
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
 */
@Component
@ConditionalOnProperty(prefix = "alert", name = "mode", havingValue = "WORKER")
public class WorkerRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);
    private static final String SUMMARY = "worker run status={} run_id={} window_from={} window_to={} "
            + "search_attempts={} accepted={} duplicates={} rejected={} error_code={}";

    private final IngestionJob job;
    private final Clock clock;
    private int exitCode;

    public WorkerRunner(IngestionJob job, Clock clock) {
        this.job = job;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        IngestionOutcome outcome = job.runScheduledWindow(clock.instant());
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
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
