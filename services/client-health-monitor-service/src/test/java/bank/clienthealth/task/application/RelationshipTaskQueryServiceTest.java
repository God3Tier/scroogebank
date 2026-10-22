package bank.clienthealth.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.ClientHealthFinding.TransactionActivityDeclineEvidence;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AdminViewer;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AgentViewer;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.InvalidCursorException;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.TaskFilter;
import bank.clienthealth.task.support.InMemoryRelationshipTaskQueryPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelationshipTaskQueryServiceTest {
    private final InMemoryRelationshipTaskQueryPort queries = new InMemoryRelationshipTaskQueryPort();
    private final RelationshipTaskQueryService service = new RelationshipTaskQueryService(queries);

    @BeforeEach
    void setUp() {
        queries.add(task("task-old", "client-1", "agent-a", Severity.MEDIUM, "2026-10-20T08:00:00Z"));
        queries.add(task("task-new", "client-2", "agent-a", Severity.HIGH, "2026-10-21T08:00:00Z"));
        queries.add(task("task-other", "client-3", "agent-b", Severity.HIGH, "2026-10-22T08:00:00Z"));
    }

    @Test
    void agentListIsScopedFilteredAndCursorPaged() {
        var filter = new TaskFilter(Set.of(RelationshipTask.Status.OPEN), Set.of(), 1, null);

        var first = service.list(new AgentViewer("agent-a"), filter);
        var second = service.list(new AgentViewer("agent-a"),
                new TaskFilter(Set.of(RelationshipTask.Status.OPEN), Set.of(), 1, first.nextCursor()));

        assertThat(first.items()).extracting(item -> item.taskId()).containsExactly("task-new");
        assertThat(second.items()).extracting(item -> item.taskId()).containsExactly("task-old");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void adminCanReadAcrossAgentsOrFilterByAssignment() {
        assertThat(service.list(new AdminViewer(null), new TaskFilter(Set.of(), Set.of(), 100, null)).items())
                .hasSize(3);
        assertThat(service.list(new AdminViewer("agent-b"),
                new TaskFilter(Set.of(), Set.of(Severity.HIGH), 100, null)).items())
                .extracting(item -> item.taskId()).containsExactly("task-other");
    }

    @Test
    void hiddenAndMissingTasksHaveTheSameResult() {
        assertThatThrownBy(() -> service.detail("task-other", new AgentViewer("agent-a")))
                .isInstanceOf(RelationshipTaskService.TaskNotFoundException.class);
        assertThatThrownBy(() -> service.detail("missing", new AgentViewer("agent-a")))
                .isInstanceOf(RelationshipTaskService.TaskNotFoundException.class);
    }

    @Test
    void mapsOnlyContractEvidenceAndHistoryFields() {
        var detail = service.detail("task-new", new AgentViewer("agent-a"));
        var history = service.history("task-new", new AgentViewer("agent-a"));

        assertThat(detail.schemaVersion()).isEqualTo("1");
        assertThat(detail.explanation()).isNull();
        assertThat(detail.evidence()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(SignalType.TRANSACTION_ACTIVITY_DECLINE);
            assertThat(item.accountId()).isEqualTo("account-1");
            assertThat(item.ruleVersion()).isEqualTo("activity-v1");
        });
        assertThat(history.items()).singleElement().satisfies(item -> {
            assertThat(item.action()).isEqualTo(RelationshipTask.Action.CREATED);
            assertThat(item.evidenceAddedCount()).isEqualTo(1);
        });
    }

    @Test
    void rejectsInvalidOrExpiredCursor() {
        assertThatThrownBy(() -> service.list(new AgentViewer("agent-a"),
                new TaskFilter(Set.of(), Set.of(), 25, "not-a-valid-cursor")))
                .isInstanceOf(InvalidCursorException.class);
    }

    private static RelationshipTask task(
            String taskId, String clientId, String agent, Severity severity, String timestamp
    ) {
        Instant now = Instant.parse(timestamp);
        SignalWindow window = new SignalWindow(now.minusSeconds(7_200), now.minusSeconds(3_600),
                now.minusSeconds(3_600), now);
        SignalEvidence<TransactionActivityDeclineEvidence> signal = new SignalEvidence<>(
                "signal-" + taskId, "account-1", SignalType.TRANSACTION_ACTIVITY_DECLINE,
                "activity-v1", severity, window,
                new TransactionActivityDeclineEvidence(20, 10, new BigDecimal("-50.00")),
                "a".repeat(64));
        ClientHealthFinding finding = new ClientHealthFinding(
                "1", "finding-" + taskId, clientId, "run-1", "rules-v1",
                new AnalysisWindow(window.baselineStart(), window.comparisonEnd()),
                java.util.List.of(signal), new PriorityInputs(severity, 1), "correlation-1", now);
        return RelationshipTask.create(taskId, finding,
                new AssignmentSnapshot(clientId, agent, true, 1, now), now);
    }
}
