package bank.clienthealth.task.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank.clienthealth.audit.RelationshipHealthAuditEvent;
import bank.clienthealth.audit.RelationshipHealthAuditEventFactory;
import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.TaskUpsertResult;
import bank.clienthealth.contract.TaskUpsertResult.Outcome;
import bank.clienthealth.task.application.BoundedDispositionPolicy;
import bank.clienthealth.task.application.RelationshipTaskQueryService;
import bank.clienthealth.task.application.RelationshipTaskService;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot;
import bank.clienthealth.task.domain.RelationshipTask.OptimisticLockException;
import bank.clienthealth.task.domain.RelationshipTask.TaskAccessException;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AdminViewer;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AgentViewer;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.TaskFilter;
import bank.clienthealth.task.port.TaskRepository.ConflictException;
import bank.clienthealth.task.port.TaskRepository.ProcessedFinding;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcRelationshipTaskRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private JdbcRelationshipTaskRepository repository;

    @BeforeEach
    void migrateAndClearDatabase() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE relationship_health_audit_outbox,
                    processed_client_health_findings, relationship_task_history,
                    relationship_task_evidence, task_assignment_snapshots, relationship_tasks,
                    agent_daily_task_admissions RESTART IDENTITY CASCADE
                """);
        repository = new JdbcRelationshipTaskRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                JsonMapper.builder().addModule(new JavaTimeModule()).build());
    }

    @Test
    void roundTripsAggregateReplaysFindingAndRejectsStaleVersion() {
        var service = service(NOW, "agent-1", "task-1");
        ClientHealthFinding finding = finding("finding-1", "client-1", Severity.HIGH, 'a', NOW);

        var created = service.upsertFinding(finding);
        var replay = service.upsertFinding(finding);
        var accepted = service.accept("task-1", 1, "agent-1", "accept-1");
        RelationshipTask reloaded = repository.findTask("task-1", new AdminViewer(null)).orElseThrow();

        assertEquals(Outcome.CREATED, created.outcome());
        assertEquals(created, replay);
        assertEquals(2, accepted.version());
        assertThat(reloaded.assignment().agentSubject()).isEqualTo("agent-1");
        assertThat(reloaded.evidence()).singleElement()
                .extracting(RelationshipTask.Evidence::findingId).isEqualTo("finding-1");
        assertThat(reloaded.history()).extracting(RelationshipTask.HistoryEntry::action)
                .containsExactly(RelationshipTask.Action.CREATED, RelationshipTask.Action.ACCEPTED);
        assertThat(jdbc.queryForList(
                "SELECT action FROM relationship_health_audit_outbox", String.class))
                .containsExactlyInAnyOrder("SIGNAL_DETECTED", "TASK_CREATED", "TASK_ACCEPTED");
        assertThatThrownBy(() -> service.accept("task-1", 1, "agent-1", "stale"))
                .isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void currentAssignmentControlsListDetailAndMutation() {
        AtomicReference<String> currentAgent = new AtomicReference<>("agent-old");
        var service = service(NOW, currentAgent, "task-1");
        service.upsertFinding(finding("finding-1", "client-1", Severity.HIGH, 'a', NOW));
        currentAgent.set("agent-new");
        var queries = new RelationshipTaskQueryService(repository,
                clientId -> assignment(clientId, currentAgent.get(), NOW.plusSeconds(60)));

        assertThat(queries.list(new AgentViewer("agent-old"), emptyFilter()).items()).isEmpty();
        assertThatThrownBy(() -> queries.detail("task-1", new AgentViewer("agent-old")))
                .isInstanceOf(RelationshipTaskService.TaskNotFoundException.class);
        assertThat(queries.list(new AgentViewer("agent-new"), emptyFilter()).items())
                .extracting(item -> item.taskId()).containsExactly("task-1");
        assertThat(queries.detail("task-1", new AgentViewer("agent-new")).assignedAgentSubject())
                .isEqualTo("agent-new");
        assertThat(queries.list(new AdminViewer(null), emptyFilter()).items()).hasSize(1);
        assertThat(queries.list(new AdminViewer("agent-old"), emptyFilter()).items()).isEmpty();
        assertThat(queries.list(new AdminViewer("agent-new"), emptyFilter()).items()).hasSize(1);
        assertThatThrownBy(() -> service.accept("task-1", 1, "agent-old", "former"))
                .isInstanceOf(TaskAccessException.class);
        assertThat(service.accept("task-1", 1, "agent-new", "current").assignment().agentSubject())
                .isEqualTo("agent-new");
    }

    @Test
    void keysetPagingIsStableWhenRowsAreInsertedOrUpdatedBetweenPages() {
        for (int index = 1; index <= 4; index++) {
            Instant time = NOW.plusSeconds(index * 60L);
            service(time, "agent-1", "task-" + index).upsertFinding(
                    finding("finding-" + index, "client-" + index, Severity.LOW,
                            (char) ('a' + index), time));
        }
        var first = repository.findTasks(new AdminViewer(null),
                new TaskFilter(Set.of(), Set.of(), 2, null));

        service(NOW.plusSeconds(600), "agent-1", "task-new").upsertFinding(
                finding("finding-new", "client-new", Severity.LOW, 'f', NOW.plusSeconds(600)));
        service(NOW.plusSeconds(700), "agent-1", "unused")
                .accept("task-2", 1, "agent-1", "updated-between-pages");
        var second = repository.findTasks(new AdminViewer(null),
                new TaskFilter(Set.of(), Set.of(), 2, first.nextCursor()));

        assertThat(first.tasks()).extracting(RelationshipTask::taskId)
                .containsExactly("task-4", "task-3");
        assertThat(second.tasks()).extracting(RelationshipTask::taskId).containsExactly("task-1");
        assertThat(first.nextCursor()).doesNotMatch("[0-9]+");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void persistsAndReplaysCooldownSuppression() {
        var service = service(NOW, "agent-1", "task-1");
        service.upsertFinding(finding("finding-1", "client-1", Severity.LOW, 'a', NOW));
        service.accept("task-1", 1, "agent-1", "accept");
        service.complete("task-1", 2, "agent-1", "complete", "CLIENT_REVIEW_COMPLETED", null);
        var later = service(NOW.plusSeconds(86_400), "agent-1", "task-2");
        ClientHealthFinding suppressedFinding = finding(
                "finding-2", "client-1", Severity.LOW, 'b', NOW.plusSeconds(86_400));

        var suppressed = later.upsertFinding(suppressedFinding);

        assertEquals(Outcome.SUPPRESSED_COOLDOWN, suppressed.outcome());
        assertEquals(suppressed, later.upsertFinding(suppressedFinding));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM processed_client_health_findings
                WHERE finding_id = 'finding-2' AND task_id IS NULL
                  AND outcome = 'SUPPRESSED_COOLDOWN' AND result_version = 0
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void reservesAtMostTheDailyAgentCap() {
        LocalDate date = LocalDate.of(2026, 10, 23);
        for (int count = 0; count < 20; count++) {
            boolean reserved = repository.<Boolean>inTransaction(session ->
                    session.tryReserveTaskCreation("agent-1", date, 20));
            assertTrue(reserved);
        }
        boolean beyondCap = repository.<Boolean>inTransaction(session ->
                session.tryReserveTaskCreation("agent-1", date, 20));
        assertFalse(beyondCap);
    }

    @Test
    void rollsBackTaskEvidenceHistoryAndFindingWhenAuditInsertFails() {
        RelationshipTask existing = RelationshipTask.create("task-existing",
                finding("finding-existing", "client-existing", Severity.HIGH, 'a', NOW),
                assignment("client-existing", "agent-1", NOW), NOW);
        var duplicateEvent = RelationshipHealthAuditEventFactory.taskHistory(
                existing.taskId(), existing.clientId(), existing.history().getLast());
        repository.inTransaction(session -> {
            session.enqueueAudit(duplicateEvent);
            return null;
        });
        var conflictingEvent = new RelationshipHealthAuditEvent(
                duplicateEvent.eventId(), duplicateEvent.action(),
                "DIFFERENT_ACTOR", duplicateEvent.payload());
        ClientHealthFinding rolledBackFinding = finding(
                "finding-rollback", "client-rollback", Severity.HIGH, 'b', NOW);
        RelationshipTask rolledBack = RelationshipTask.create("task-rollback", rolledBackFinding,
                assignment("client-rollback", "agent-1", NOW), NOW);

        assertThatThrownBy(() -> repository.inTransaction(session -> {
            session.saveTask(rolledBack, 0);
            session.saveProcessedFinding(new ProcessedFinding(rolledBackFinding,
                    new TaskUpsertResult("task-rollback", Outcome.CREATED, 1), NOW));
            session.enqueueAudit(conflictingEvent);
            return null;
        })).isInstanceOf(ConflictException.class);

        assertThat(repository.findTask("task-rollback", new AdminViewer(null))).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM processed_client_health_findings
                WHERE finding_id = 'finding-rollback'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM relationship_task_evidence
                WHERE task_id = 'task-rollback'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM relationship_task_history
                WHERE task_id = 'task-rollback'
                """, Integer.class)).isZero();
    }

    private RelationshipTaskService service(Instant now, String agent, String taskId) {
        return service(now, new AtomicReference<>(agent), taskId);
    }

    private RelationshipTaskService service(Instant now, AtomicReference<String> agent, String taskId) {
        return new RelationshipTaskService(repository,
                clientId -> assignment(clientId, agent.get(), now),
                new BoundedDispositionPolicy(), Clock.fixed(now, ZoneOffset.UTC), () -> taskId);
    }

    private static TaskFilter emptyFilter() {
        return new TaskFilter(Set.of(), Set.of(), 25, null);
    }

    private static AssignmentSnapshot assignment(String clientId, String agent, Instant now) {
        return new AssignmentSnapshot(clientId, agent, true, 2, now);
    }

    private static ClientHealthFinding finding(
            String findingId, String clientId, Severity severity, char fingerprint, Instant now
    ) {
        Instant baselineStart = now.minusSeconds(120L * 86_400);
        Instant comparisonStart = now.minusSeconds(30L * 86_400);
        SignalWindow window = new SignalWindow(
                baselineStart, comparisonStart, comparisonStart, now.minusSeconds(60));
        var signal = new SignalEvidence<>("signal-" + findingId, "account-" + clientId,
                SignalType.RECURRING_CREDIT_INTERRUPTION, "rule-v1", severity, window,
                new RecurringCreditInterruptionEvidence(30, 3, 60),
                Character.toString(fingerprint).repeat(64));
        return new ClientHealthFinding("1", findingId, clientId, "run-" + findingId, "rules-v1",
                new AnalysisWindow(baselineStart, now), java.util.List.of(signal),
                new PriorityInputs(severity, 1), "correlation-" + findingId, now);
    }
}
