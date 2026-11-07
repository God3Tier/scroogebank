package bank.clienthealth.contract;

import static bank.clienthealth.contract.ClientHealthFinding.SCHEMA_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.ClientHealthFinding.TransactionActivityDeclineEvidence;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientHealthFindingTest {

    private static final Instant ANALYSIS_START = Instant.parse("2026-05-31T00:00:00Z");
    private static final Instant BASELINE_END = Instant.parse("2026-08-29T00:00:00Z");
    private static final Instant ANALYSIS_END = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void acceptsValidFindingAndDefensivelyCopiesSignals() {
        List<SignalEvidence<? extends ClientHealthFinding.EvidenceDetails>> mutableSignals =
                new ArrayList<>(List.of(recurringCreditSignal("a".repeat(64))));

        ClientHealthFinding finding = finding(mutableSignals, Severity.MEDIUM);
        mutableSignals.clear();

        assertEquals(1, finding.signals().size());
        assertNotSame(mutableSignals, finding.signals());
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        assertThrows(IllegalArgumentException.class, () -> new ClientHealthFinding(
                "2",
                "finding-1",
                "client-1",
                "run-1",
                "relationship-health-v1",
                analysisWindow(),
                List.of(recurringCreditSignal("a".repeat(64))),
                new PriorityInputs(Severity.MEDIUM, 1),
                "correlation-1",
                Instant.parse("2026-09-29T00:00:00Z")));
    }

    @Test
    void rejectsEmptySignalList() {
        assertThrows(IllegalArgumentException.class, () -> finding(List.of(), Severity.LOW));
    }

    @Test
    void rejectsDuplicateSignalFingerprints() {
        String fingerprint = "b".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> finding(
                List.of(recurringCreditSignal(fingerprint), recurringCreditSignal(fingerprint)),
                Severity.MEDIUM));
    }

    @Test
    void rejectsMismatchedEvidenceType() {
        assertThrows(IllegalArgumentException.class, () -> new SignalEvidence<>(
                "signal-1",
                "account-1",
                SignalType.TRANSACTION_ACTIVITY_DECLINE,
                "activity-decline-v1",
                Severity.MEDIUM,
                signalWindow(),
                new RecurringCreditInterruptionEvidence(30, 3, 67),
                "c".repeat(64)));
    }

    @Test
    void rejectsPriorityThatDoesNotMatchSignals() {
        assertThrows(IllegalArgumentException.class, () -> finding(
                List.of(recurringCreditSignal("d".repeat(64))),
                Severity.HIGH));
    }

    @Test
    void rejectsSignalWindowOutsideAnalysisWindow() {
        SignalWindow outside = new SignalWindow(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z"));

        SignalEvidence<RecurringCreditInterruptionEvidence> signal = new SignalEvidence<>(
                "signal-1",
                "account-1",
                SignalType.RECURRING_CREDIT_INTERRUPTION,
                "recurring-credit-v1",
                Severity.MEDIUM,
                outside,
                new RecurringCreditInterruptionEvidence(30, 3, 67),
                "e".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> finding(List.of(signal), Severity.MEDIUM));
    }

    @Test
    void rejectsNonDecliningActivityEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionActivityDeclineEvidence(
                24,
                24,
                BigDecimal.ZERO));
    }

    @Test
    void acceptsTaskUpsertResult() {
        TaskUpsertResult result =
                new TaskUpsertResult("task-1", TaskUpsertResult.Outcome.CREATED, 1);

        assertEquals(TaskUpsertResult.Outcome.CREATED, result.outcome());
    }

    @Test
    void rejectsNonPositiveTaskVersion() {
        assertThrows(IllegalArgumentException.class, () -> new TaskUpsertResult(
                "task-1",
                TaskUpsertResult.Outcome.CREATED,
                0));
    }

    @Test
    void acceptsOnlyExplicitTasklessSuppressionResults() {
        TaskUpsertResult suppressed = TaskUpsertResult.suppressed(
                TaskUpsertResult.Outcome.SUPPRESSED_COOLDOWN);

        assertEquals(TaskUpsertResult.Outcome.SUPPRESSED_COOLDOWN, suppressed.outcome());
        assertNull(suppressed.taskId());
        assertEquals(0, suppressed.taskVersion());
        assertThrows(IllegalArgumentException.class, () -> TaskUpsertResult.suppressed(
                TaskUpsertResult.Outcome.CREATED));
        assertThrows(IllegalArgumentException.class, () -> new TaskUpsertResult(
                "task-1", TaskUpsertResult.Outcome.SUPPRESSED_DAILY_CAP, 1));
    }

    private static ClientHealthFinding finding(
            List<SignalEvidence<? extends ClientHealthFinding.EvidenceDetails>> signals,
            Severity highestSeverity
    ) {
        return new ClientHealthFinding(
                SCHEMA_VERSION,
                "finding-1",
                "client-1",
                "run-1",
                "relationship-health-v1",
                analysisWindow(),
                signals,
                new PriorityInputs(highestSeverity, Math.max(1, signals.size())),
                "correlation-1",
                Instant.parse("2026-09-29T00:00:00Z"));
    }

    private static AnalysisWindow analysisWindow() {
        return new AnalysisWindow(ANALYSIS_START, ANALYSIS_END);
    }

    private static SignalWindow signalWindow() {
        return new SignalWindow(ANALYSIS_START, BASELINE_END, BASELINE_END, ANALYSIS_END);
    }

    private static SignalEvidence<RecurringCreditInterruptionEvidence> recurringCreditSignal(
            String fingerprint
    ) {
        return new SignalEvidence<>(
                "signal-" + fingerprint.charAt(0),
                "account-1",
                SignalType.RECURRING_CREDIT_INTERRUPTION,
                "recurring-credit-v1",
                Severity.MEDIUM,
                signalWindow(),
                new RecurringCreditInterruptionEvidence(30, 3, 67),
                fingerprint);
    }
}
