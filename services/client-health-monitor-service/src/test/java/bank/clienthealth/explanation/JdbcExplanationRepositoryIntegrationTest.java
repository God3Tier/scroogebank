package bank.clienthealth.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.clienthealth.audit.RelationshipHealthAuditEvent;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.Action;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.AuditState;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.EntityType;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.Payload;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.task.domain.RelationshipTask.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
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
class JdbcExplanationRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private JdbcExplanationRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE relationship_task_explanations, relationship_health_audit_outbox,
                    processed_client_health_findings, relationship_task_history,
                    relationship_task_evidence, task_assignment_snapshots, relationship_tasks,
                    agent_daily_task_admissions RESTART IDENTITY CASCADE
                """);
        repository = new JdbcExplanationRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
        jdbc.update("""
                INSERT INTO relationship_tasks
                    (task_id, client_id, status, severity, version, created_at, updated_at)
                VALUES ('task-1', 'client-1', 'OPEN', 'HIGH', 1, now(), now())
                """);
        jdbc.update("""
                INSERT INTO processed_client_health_findings
                    (finding_id, task_id, finding_payload, outcome, result_version, processed_at)
                VALUES ('finding-1', 'task-1', '{}'::jsonb, 'CREATED', 1, now())
                """);
    }

    @Test
    void savesReloadsAndDeduplicatesExplanationWithAudit() {
        var value = record("a".repeat(64));
        var audit = audit("b".repeat(64), value.explanationId());

        assertThat(repository.saveIfAbsent(value, audit)).isEqualTo(value);
        assertThat(repository.saveIfAbsent(value, audit)).isEqualTo(value);
        assertThat(repository.findForTaskVersion("task-1", 1)).contains(value);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM relationship_task_explanations", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM relationship_health_audit_outbox", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT action FROM relationship_health_audit_outbox", String.class))
                .isEqualTo("EXPLANATION_FALLBACK_USED");
    }

    @Test
    void rollsBackExplanationWhenAuditInsertFails() {
        String eventId = "c".repeat(64);
        jdbc.update("""
                INSERT INTO relationship_health_audit_outbox
                    (event_id, event_type, action, source_service, actor_id, payload,
                     created_at, next_attempt_at)
                VALUES (?, 'RELATIONSHIP_HEALTH', 'TASK_CREATED',
                        'RELATIONSHIP_HEALTH_SERVICE', 'SYSTEM', '{}'::jsonb, now(), now())
                """, eventId);

        assertThatThrownBy(() -> repository.saveIfAbsent(record("d".repeat(64)),
                audit(eventId, "d".repeat(64)))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM relationship_task_explanations", Integer.class)).isZero();
    }

    private static ExplanationRepository.Record record(String id) {
        return new ExplanationRepository.Record(id, "task-1", "finding-1", 1,
                "Verified activity changed.", "Review verified activity.",
                ExplanationResult.DISCLAIMER, List.of(SignalType.RECURRING_CREDIT_INTERRUPTION),
                ExplanationResult.Source.TEMPLATE_FALLBACK,
                ExplanationRequest.PROMPT_VERSION, null, Instant.parse("2026-10-26T00:00:00Z"));
    }

    private static RelationshipHealthAuditEvent audit(String eventId, String explanationId) {
        var payload = new Payload("1", EntityType.EXPLANATION, explanationId,
                Instant.parse("2026-10-26T00:00:00Z"), "correlation-1", "e".repeat(64), null,
                new AuditState(Status.OPEN, Severity.HIGH, 1L), ExplanationRequest.PROMPT_VERSION);
        return new RelationshipHealthAuditEvent(eventId, Action.EXPLANATION_FALLBACK_USED,
                "SYSTEM", payload);
    }
}
