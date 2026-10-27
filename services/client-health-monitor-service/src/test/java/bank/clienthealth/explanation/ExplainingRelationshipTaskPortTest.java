package bank.clienthealth.explanation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.RelationshipTaskPort;
import bank.clienthealth.contract.TaskUpsertResult;
import bank.clienthealth.contract.TaskUpsertResult.Outcome;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot;
import bank.clienthealth.task.port.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ExplainingRelationshipTaskPortTest {
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    @Test
    void duplicateRetryRepairsExplanationMissingAfterInitialPersistenceFailure() {
        ClientHealthFinding finding = finding();
        RelationshipTask task = RelationshipTask.create(
                "task-1", finding,
                new AssignmentSnapshot("client-1", "agent-1", true, 1, NOW), NOW);
        RelationshipTaskPort tasks = mock(RelationshipTaskPort.class);
        when(tasks.upsertFinding(finding))
                .thenReturn(new TaskUpsertResult("task-1", Outcome.CREATED, 1))
                .thenReturn(new TaskUpsertResult("task-1", Outcome.DUPLICATE, 1));

        TaskRepository taskRepository = mock(TaskRepository.class);
        when(taskRepository.inTransaction(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<TaskRepository.Session, Object> work = invocation.getArgument(0);
            TaskRepository.Session session = mock(TaskRepository.Session.class);
            when(session.findTask("task-1")).thenReturn(Optional.of(task));
            return work.apply(session);
        });

        ExplanationRepository explanations = mock(ExplanationRepository.class);
        var existing = new ExplanationRepository.Record(
                "a".repeat(64), "task-1", "finding-1", 1,
                "Verified activity changed.", "Review verified activity.",
                ExplanationResult.DISCLAIMER,
                List.of(SignalType.RECURRING_CREDIT_INTERRUPTION),
                ExplanationResult.Source.TEMPLATE_FALLBACK,
                ExplanationRequest.PROMPT_VERSION, null, NOW);
        when(explanations.find(
                "task-1", "finding-1", 1, ExplanationRequest.PROMPT_VERSION))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(existing));
        when(explanations.saveIfAbsent(any(), any()))
                .thenThrow(new IllegalStateException("synthetic database outage"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExplanationProvider provider = mock(ExplanationProvider.class);
        when(provider.explain(any())).thenReturn(new TemplateExplanationProvider().explain(
                ExplanationRequestFactory.from(finding)));
        var explaining = new ExplainingRelationshipTaskPort(
                tasks, taskRepository, explanations, provider,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, () -> explaining.upsertFinding(finding));
        assertEquals(Outcome.DUPLICATE, explaining.upsertFinding(finding).outcome());
        assertEquals(Outcome.DUPLICATE, explaining.upsertFinding(finding).outcome());

        verify(provider, times(2)).explain(any());
        verify(explanations, times(2)).saveIfAbsent(any(), any());
    }

    private static ClientHealthFinding finding() {
        Instant baseline = NOW.minusSeconds(120L * 86_400);
        Instant comparison = NOW.minusSeconds(30L * 86_400);
        SignalWindow window = new SignalWindow(baseline, comparison, comparison, NOW);
        var signal = new SignalEvidence<>(
                "signal-1", "account-1", SignalType.RECURRING_CREDIT_INTERRUPTION,
                "rule-v1", Severity.HIGH, window,
                new RecurringCreditInterruptionEvidence(30, 3, 67), "a".repeat(64));
        return new ClientHealthFinding(
                "1", "finding-1", "client-1", "run-1", "rules-v1",
                new AnalysisWindow(baseline, NOW), List.of(signal),
                new PriorityInputs(Severity.HIGH, 1), "correlation-1", NOW);
    }
}
