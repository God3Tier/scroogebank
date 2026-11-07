package bank.clienthealth.runtime;

import bank.clienthealth.analysis.application.RelationshipHealthAnalysisOrchestrator;
import bank.clienthealth.analysis.application.RelationshipHealthAnalysisOrchestrator.RunStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/** Runs one Singapore analysis date and supplies the process exit code. */
public final class AnalysisRunner implements ApplicationRunner, ExitCodeGenerator {
    static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

    private final RelationshipHealthAnalysisOrchestrator orchestrator;
    private final Clock clock;
    private int exitCode;

    public AnalysisRunner(RelationshipHealthAnalysisOrchestrator orchestrator, Clock clock) {
        this.orchestrator = orchestrator;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        LocalDate analysisDate = LocalDate.now(clock.withZone(SINGAPORE));
        try {
            RunStatus status = orchestrator.run(analysisDate).status();
            exitCode = status == RunStatus.COMPLETED || status == RunStatus.ALREADY_COMPLETED ? 0 : 1;
            if (exitCode == 0) {
                log.info("client_health_analysis status={} analysis_date={}", status, analysisDate);
            } else {
                log.error("client_health_analysis status=LEASE_HELD analysis_date={}", analysisDate);
            }
        } catch (RuntimeException failure) {
            exitCode = 1;
            log.error("client_health_analysis status=FAILED analysis_date={}", analysisDate);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
