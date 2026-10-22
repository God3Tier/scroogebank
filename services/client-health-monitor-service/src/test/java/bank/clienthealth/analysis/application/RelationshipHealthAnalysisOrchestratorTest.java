package bank.clienthealth.analysis.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bank.clienthealth.activity.application.ActivityBaselineService;
import bank.clienthealth.activity.application.ActivityProjectionService;
import bank.clienthealth.activity.support.InMemoryActivityProjectionRepository;
import bank.clienthealth.analysis.application.RelationshipHealthAnalysisOrchestrator.AnalysisRunException;
import bank.clienthealth.analysis.application.RelationshipHealthAnalysisOrchestrator.RunStatus;
import bank.clienthealth.analysis.port.AnalysisRunPort.AcquisitionStatus;
import bank.clienthealth.analysis.port.AnalysisRunPort.FailureCategory;
import bank.clienthealth.analysis.support.InMemoryAnalysisRunPort;
import bank.clienthealth.analysis.support.InMemoryAnalysisRunPort.RunState;
import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.RelationshipTaskPort;
import bank.clienthealth.contract.TaskUpsertResult;
import bank.clienthealth.contract.TaskUpsertResult.Outcome;
import bank.clienthealth.rules.RelationshipHealthRuleEngine;
import bank.clienthealth.rules.RelationshipHealthRuleset;
import bank.clienthealth.transaction.adapter.mock.SyntheticTransactionReplaySource;
import bank.clienthealth.transaction.application.TransactionReplayService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RelationshipHealthAnalysisOrchestratorTest {
    private static final LocalDate ANALYSIS_DATE = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = Instant.parse("2026-10-01T00:05:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-01T00:00:00Z");
    private static final String RUN_ID =
            "analysis-2026-10-01-relationship-health-v1";

    @Test
    void completesOnceWithDeterministicOneFindingPerClient() {
        var runPort = new InMemoryAnalysisRunPort();
        var findings = new ArrayList<ClientHealthFinding>();
        RelationshipTaskPort taskPort = finding -> {
            findings.add(finding);
            return new TaskUpsertResult("task-" + findings.size(), Outcome.CREATED, 1);
        };
        var orchestrator = orchestrator(taskPort, runPort);

        var completed = orchestrator.run(ANALYSIS_DATE);
        var repeated = orchestrator.run(ANALYSIS_DATE);

        assertEquals(RunStatus.COMPLETED, completed.status());
        assertEquals(3L, completed.taskOutcomes().get(Outcome.CREATED));
        assertEquals(RunStatus.ALREADY_COMPLETED, repeated.status());
        assertEquals(3, findings.size());
        assertEquals(List.of("client-activity", "client-outflow", "client-recurring"),
                findings.stream().map(ClientHealthFinding::clientId).toList());
        findings.forEach(finding -> {
            assertEquals(RUN_ID, finding.analysisRunId());
            assertEquals("relationship-health-v1", finding.rulesetVersion());
            assertEquals(CUTOFF, finding.detectedAt());
            assertEquals(1, finding.signals().size());
            assertEquals(64, finding.signals().getFirst().fingerprint().length());
        });
        var run = runPort.view(RUN_ID);
        assertEquals(RunState.SUCCEEDED, run.status());
        assertEquals(CUTOFF, run.watermark());
    }

    @Test
    void treatsExplicitAttachmentDuplicateAndSuppressionAsSuccessfulOutcomes() {
        var runPort = new InMemoryAnalysisRunPort();
        List<Outcome> outcomes = List.of(
                Outcome.EVIDENCE_ATTACHED,
                Outcome.SUPPRESSED_COOLDOWN,
                Outcome.SUPPRESSED_DAILY_CAP);
        var sequence = new AtomicInteger();
        RelationshipTaskPort taskPort = finding -> {
            Outcome outcome = outcomes.get(sequence.getAndIncrement());
            return outcome.suppressed()
                    ? TaskUpsertResult.suppressed(outcome)
                    : new TaskUpsertResult("task-existing", outcome, 2);
        };

        var result = orchestrator(taskPort, runPort).run(ANALYSIS_DATE);

        assertEquals(RunStatus.COMPLETED, result.status());
        outcomes.forEach(outcome -> assertEquals(1L, result.taskOutcomes().get(outcome)));
        assertEquals(CUTOFF, runPort.view(RUN_ID).watermark());
    }

    @Test
    void refusesAConcurrentLiveLeaseWithoutCallingTasks() {
        var runPort = new InMemoryAnalysisRunPort();
        var acquired = runPort.acquire(RUN_ID, CUTOFF, NOW, Duration.ofMinutes(60));
        assertEquals(AcquisitionStatus.ACQUIRED, acquired.status());
        var taskCalls = new AtomicInteger();
        RelationshipTaskPort taskPort = finding -> {
            taskCalls.incrementAndGet();
            return new TaskUpsertResult("task-1", Outcome.CREATED, 1);
        };

        var result = orchestrator(taskPort, runPort).run(ANALYSIS_DATE);

        assertEquals(RunStatus.LEASE_HELD, result.status());
        assertEquals(0, taskCalls.get());
        assertEquals(RunState.RUNNING, runPort.view(RUN_ID).status());
    }

    @Test
    void leavesNoWatermarkOnPartialFailureAndRetriesEquivalentFindings() {
        var runPort = new InMemoryAnalysisRunPort();
        var firstAttempt = new ArrayList<ClientHealthFinding>();
        var calls = new AtomicInteger();
        RelationshipTaskPort failing = finding -> {
            firstAttempt.add(finding);
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("task database unavailable");
            }
            return new TaskUpsertResult("task-1", Outcome.CREATED, 1);
        };

        AnalysisRunException failure = assertThrows(
                AnalysisRunException.class,
                () -> orchestrator(failing, runPort).run(ANALYSIS_DATE));

        assertEquals(FailureCategory.TASK_HANDOFF, failure.category());
        assertEquals(RunState.FAILED, runPort.view(RUN_ID).status());
        assertNull(runPort.view(RUN_ID).watermark());

        var retryFindings = new ArrayList<ClientHealthFinding>();
        RelationshipTaskPort retry = finding -> {
            retryFindings.add(finding);
            return new TaskUpsertResult(
                    "task-" + retryFindings.size(),
                    retryFindings.size() == 1 ? Outcome.DUPLICATE : Outcome.CREATED,
                    1);
        };
        var result = orchestrator(retry, runPort).run(ANALYSIS_DATE);

        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals(firstAttempt.getFirst(), retryFindings.getFirst());
        assertEquals(2, runPort.view(RUN_ID).attempts());
        assertEquals(CUTOFF, runPort.view(RUN_ID).watermark());
    }

    private static RelationshipHealthAnalysisOrchestrator orchestrator(
            RelationshipTaskPort taskPort,
            InMemoryAnalysisRunPort runPort
    ) {
        var projectionRepository = new InMemoryActivityProjectionRepository();
        return new RelationshipHealthAnalysisOrchestrator(
                new TransactionReplayService(
                        SyntheticTransactionReplaySource.sixMonthScenario(), 10, 10, 100),
                new ActivityProjectionService(projectionRepository),
                new ActivityBaselineService(projectionRepository),
                new RelationshipHealthRuleEngine(RelationshipHealthRuleset.v1()),
                taskPort,
                runPort,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(60));
    }
}
