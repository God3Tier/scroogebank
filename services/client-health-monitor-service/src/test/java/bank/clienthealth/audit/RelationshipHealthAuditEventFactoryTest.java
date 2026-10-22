package bank.clienthealth.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bank.clienthealth.audit.RelationshipHealthAuditEvent.Action;
import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.TaskUpsertResult.Outcome;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.domain.RelationshipTask.HistoryEntry;
import bank.clienthealth.task.domain.RelationshipTask.Status;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelationshipHealthAuditEventFactoryTest {
    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Test
    void mapsTaskHistoryDeterministicallyWithoutNotesOrRawClientIds() {
        HistoryEntry history = new HistoryEntry(
                2, RelationshipTask.Action.ACCEPTED, "agent-1", "correlation-1", NOW,
                Status.OPEN, Status.IN_REVIEW, Severity.HIGH, Severity.HIGH,
                List.of(), null, "private synthetic note");

        var first = RelationshipHealthAuditEventFactory.taskHistory(
                "task-1", "client-secret-1", history);
        var repeated = RelationshipHealthAuditEventFactory.taskHistory(
                "task-1", "client-secret-1", history);

        assertEquals(first, repeated);
        assertEquals(Action.TASK_ACCEPTED, first.action());
        assertEquals("agent-1", first.actorId());
        assertEquals(Status.OPEN, first.payload().before().status());
        assertEquals(Status.IN_REVIEW, first.payload().after().status());
        assertEquals(64, first.payload().clientRef().length());
        assertFalse(first.toString().contains("client-secret-1"));
        assertFalse(first.toString().contains("private synthetic note"));
        assertNull(first.payload().dispositionCode());
    }

    @Test
    void mapsSuppressionWithoutCreatingATaskReference() {
        var event = RelationshipHealthAuditEventFactory.suppression(
                finding(), Outcome.SUPPRESSED_DAILY_CAP);

        assertEquals(Action.SIGNAL_SUPPRESSED, event.action());
        assertEquals("SYSTEM", event.actorId());
        assertEquals("finding-1", event.payload().entityId());
        assertEquals("SUPPRESSED_DAILY_CAP", event.payload().dispositionCode());
        assertEquals(Severity.HIGH, event.payload().after().severity());
        assertFalse(event.toString().contains("client-1"));
        assertThrows(IllegalArgumentException.class,
                () -> RelationshipHealthAuditEventFactory.suppression(
                        finding(), Outcome.CREATED));
    }

    @Test
    void acceptsOnlyAnalysisActionsForAnalysisEvents() {
        var event = RelationshipHealthAuditEventFactory.analysis(
                "run-1", Action.ANALYSIS_RUN_FAILED, NOW,
                "correlation-run-1", "TASK_HANDOFF");

        assertEquals(Action.ANALYSIS_RUN_FAILED, event.action());
        assertNull(event.payload().clientRef());
        assertThrows(IllegalArgumentException.class,
                () -> RelationshipHealthAuditEventFactory.analysis(
                        "run-1", Action.TASK_CREATED, NOW,
                        "correlation-run-1", null));
    }

    private static ClientHealthFinding finding() {
        Instant start = NOW.minusSeconds(120 * 86_400L);
        Instant comparison = NOW.minusSeconds(30 * 86_400L);
        SignalEvidence<RecurringCreditInterruptionEvidence> signal = new SignalEvidence<>(
                "signal-1", "account-1", SignalType.RECURRING_CREDIT_INTERRUPTION,
                "rule-1", Severity.HIGH,
                new SignalWindow(start, comparison, comparison, NOW),
                new RecurringCreditInterruptionEvidence(30, 3, 90),
                "a".repeat(64));
        return new ClientHealthFinding(
                "1", "finding-1", "client-1", "run-1", "rules-1",
                new AnalysisWindow(start, NOW), List.of(signal),
                new PriorityInputs(Severity.HIGH, 1), "correlation-1", NOW);
    }
}
