package bank.clienthealth.task.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.TaskUpsertResult.Outcome;
import bank.clienthealth.task.application.BoundedDispositionPolicy;
import bank.clienthealth.task.application.RelationshipTaskService;
import bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot;
import bank.clienthealth.task.domain.RelationshipTask.OptimisticLockException;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AgentViewer;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.TaskFilter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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
    void persistsReadsReplaysCompareAndSetsAndEnqueuesAuditAtomically() {
        Instant now = Instant.parse("2026-10-23T00:00:00Z");
        var service = new RelationshipTaskService(repository,
                clientId -> new AssignmentSnapshot(clientId, "agent-1", true, 1, now),
                new BoundedDispositionPolicy(), Clock.fixed(now, ZoneOffset.UTC), () -> "task-1");
        ClientHealthFinding finding = finding(now);

        var created = service.upsertFinding(finding);
        var replay = service.upsertFinding(finding);
        var page = repository.findTasks(new AgentViewer("agent-1"),
                new TaskFilter(Set.of(), Set.of(), 25, null));
        var accepted = service.accept("task-1", 1, "agent-1", "accept-1");

        assertEquals(Outcome.CREATED, created.outcome());
        assertEquals(created, replay);
        assertEquals(List.of("task-1"), page.tasks().stream().map(task -> task.taskId()).toList());
        assertEquals(2, accepted.version());
        assertEquals(2, accepted.history().size());
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM relationship_health_audit_outbox", Integer.class));
        assertThrows(OptimisticLockException.class,
                () -> service.accept("task-1", 1, "agent-1", "stale"));
    }

    private static ClientHealthFinding finding(Instant now) {
        Instant baselineStart = now.minusSeconds(120L * 86_400);
        Instant comparisonStart = now.minusSeconds(30L * 86_400);
        SignalWindow window = new SignalWindow(
                baselineStart, comparisonStart, comparisonStart, now.minusSeconds(60));
        var signal = new SignalEvidence<>("signal-1", "account-1",
                SignalType.RECURRING_CREDIT_INTERRUPTION, "rule-v1", Severity.HIGH, window,
                new RecurringCreditInterruptionEvidence(30, 3, 60), "a".repeat(64));
        return new ClientHealthFinding("1", "finding-1", "client-1", "run-1", "rules-v1",
                new AnalysisWindow(baselineStart, now), List.of(signal),
                new PriorityInputs(Severity.HIGH, 1), "correlation-1", now);
    }
}
