package bank.clienthealth.task.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RelationshipTaskMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private Flyway flyway;

    @BeforeAll
    void migrateSchema() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load();
        assertEquals(5, flyway.migrate().migrationsExecuted);
    }

    @BeforeEach
    void clearTables() throws SQLException {
        execute("TRUNCATE relationship_task_explanations, "
                + "relationship_health_analysis_runs, "
                + "client_health_replay_coverage, client_health_activity_projection, "
                + "relationship_health_audit_outbox, "
                + "processed_client_health_findings, relationship_task_history, "
                + "relationship_task_evidence, task_assignment_snapshots, relationship_tasks, "
                + "agent_daily_task_admissions "
                + "RESTART IDENTITY CASCADE");
    }

    @Test
    void createsAllTablesAndCanBeRunAgain() throws SQLException {
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN (
                              'relationship_tasks',
                              'task_assignment_snapshots',
                              'relationship_task_evidence',
                              'relationship_task_history',
                              'agent_daily_task_admissions',
                              'processed_client_health_findings',
                              'relationship_health_audit_outbox',
                              'client_health_activity_projection',
                              'client_health_replay_coverage',
                              'relationship_health_analysis_runs',
                              'relationship_task_explanations')
                        """)) {
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals(11, result.getInt(1));
            }
        }
    }

    @Test
    void enforcesVersionedExplanationContract() throws SQLException {
        insertTask("task-1", "client-1", "OPEN", 1);
        execute("INSERT INTO processed_client_health_findings VALUES "
                + "('finding-1', 'task-1', '{}'::jsonb, 'CREATED', 1, now())");
        String values = "(repeat('a', 64), 'task-1', 'finding-1', 1, 'Summary.', "
                + "'Review verified activity.', 'Human review required', "
                + "ARRAY['RECURRING_CREDIT_INTERRUPTION'], 'TEMPLATE_FALLBACK', "
                + "'relationship-health-explanation-v1', NULL, now())";
        execute("INSERT INTO relationship_task_explanations VALUES " + values);

        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO relationship_task_explanations VALUES " + values));
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO relationship_task_explanations VALUES
                (repeat('b', 64), 'task-1', 'finding-1', 2, 'Summary.',
                 'Review verified activity.', 'Human review required',
                 ARRAY['RECURRING_CREDIT_INTERRUPTION'], 'BEDROCK',
                 'relationship-health-explanation-v1', NULL, now())
                """));
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO relationship_task_explanations VALUES
                (repeat('c', 64), 'task-1', 'finding-1', 2, 'Summary.',
                 'Review verified activity.', 'Human review required',
                 ARRAY['UNKNOWN'], 'TEMPLATE_FALLBACK',
                 'relationship-health-explanation-v1', NULL, now())
                """));
    }

    @Test
    void permitsOnlyOneActiveTaskPerClient() throws SQLException {
        insertTask("task-1", "client-1", "OPEN", 1);

        assertThrows(SQLException.class,
                () -> insertTask("task-2", "client-1", "IN_REVIEW", 1));

        execute("UPDATE relationship_tasks SET status = 'COMPLETED', version = 2, "
                + "updated_at = now() WHERE task_id = 'task-1'");
        insertTask("task-2", "client-1", "OPEN", 1);
    }

    @Test
    void preservesFingerprintUniquenessAcrossClientHistory() throws SQLException {
        insertTask("task-1", "client-1", "COMPLETED", 2);
        insertTask("task-2", "client-1", "OPEN", 1);
        insertEvidence("task-1", "client-1", "a".repeat(64));

        assertThrows(SQLException.class,
                () -> insertEvidence("task-2", "client-1", "a".repeat(64)));
        assertThrows(SQLException.class,
                () -> insertEvidence("missing-task", "client-2", "b".repeat(64)));
    }

    @Test
    void rejectsInvalidTaskAndHistoryState() throws SQLException {
        assertThrows(SQLException.class,
                () -> insertTask("task-bad-status", "client-1", "UNKNOWN", 1));
        assertThrows(SQLException.class,
                () -> insertTask("task-bad-version", "client-1", "OPEN", 0));

        insertTask("task-1", "client-1", "OPEN", 1);
        insertHistory("task-1", 1, "CREATED", null, "OPEN", null);
        insertHistory("task-1", 2, "ACCEPTED", "OPEN", "IN_REVIEW", null);
        insertHistory("task-1", 3, "COMPLETED", "IN_REVIEW", "COMPLETED",
                null, "CLIENT_REVIEW_COMPLETED");
        assertThrows(SQLException.class,
                () -> insertHistory("task-1", 4, "ACCEPTED", "OPEN", "COMPLETED", null));
        assertThrows(SQLException.class,
                () -> insertHistory("task-1", 4, "DISMISSED", "OPEN", "DISMISSED", null, null));
    }

    @Test
    void enforcesAssignmentAndProcessedFindingKeys() throws SQLException {
        insertTask("task-1", "client-1", "OPEN", 1);
        execute("INSERT INTO task_assignment_snapshots VALUES "
                + "('task-1', 'client-1', 'agent-1', true, 1, now())");
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO task_assignment_snapshots VALUES "
                        + "('missing', 'client-1', 'agent-1', true, 1, now())"));

        execute("INSERT INTO processed_client_health_findings VALUES "
                + "('finding-1', 'task-1', '{}'::jsonb, 'CREATED', 1, now())");
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO processed_client_health_findings VALUES "
                        + "('finding-1', 'task-1', '{}'::jsonb, 'DUPLICATE', 1, now())"));

        execute("INSERT INTO processed_client_health_findings VALUES "
                + "('finding-2', NULL, '{}'::jsonb, 'SUPPRESSED_COOLDOWN', 0, now())");
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO processed_client_health_findings VALUES "
                        + "('finding-3', 'task-1', '{}'::jsonb, 'SUPPRESSED_DAILY_CAP', 1, now())"));
        execute("INSERT INTO agent_daily_task_admissions VALUES "
                + "('agent-1', DATE '2026-10-22', 20, now())");
        assertThrows(SQLException.class, () -> execute(
                "UPDATE agent_daily_task_admissions SET created_task_count = 21"));
    }

    @Test
    void rejectsInvalidEvidenceWindowsTypesAndFingerprints() throws SQLException {
        insertTask("task-1", "client-1", "OPEN", 1);
        Instant baselineFrom = Instant.parse("2026-05-01T00:00:00Z");
        Instant baselineTo = Instant.parse("2026-08-01T00:00:00Z");
        Instant comparisonFrom = baselineTo;
        Instant comparisonTo = Instant.parse("2026-09-01T00:00:00Z");

        assertThrows(SQLException.class, () -> insertEvidence(
                "task-1", "client-1", "not-a-sha256",
                "SUSTAINED_NET_OUTFLOW",
                baselineFrom, baselineTo, comparisonFrom, comparisonTo));
        assertThrows(SQLException.class, () -> insertEvidence(
                "task-1", "client-1", "a".repeat(64),
                "UNKNOWN_SIGNAL",
                baselineFrom, baselineTo, comparisonFrom, comparisonTo));
        assertThrows(SQLException.class, () -> insertEvidence(
                "task-1", "client-1", "b".repeat(64),
                "TRANSACTION_ACTIVITY_DECLINE",
                baselineFrom, baselineFrom, comparisonFrom, comparisonTo));
        assertThrows(SQLException.class, () -> insertEvidence(
                "task-1", "client-1", "c".repeat(64),
                "TRANSACTION_ACTIVITY_DECLINE",
                baselineFrom, baselineTo, comparisonFrom, comparisonFrom));
        assertThrows(SQLException.class, () -> insertEvidence(
                "task-1", "client-1", "d".repeat(64),
                "TRANSACTION_ACTIVITY_DECLINE",
                baselineFrom, comparisonTo, comparisonFrom, comparisonTo));
    }

    @Test
    void enforcesAuditOutboxContractAndLeaseState() throws SQLException {
        execute("""
                INSERT INTO relationship_health_audit_outbox
                    (event_id, event_type, action, source_service, actor_id, payload,
                     created_at, next_attempt_at)
                VALUES (repeat('a', 64), 'RELATIONSHIP_HEALTH', 'TASK_CREATED',
                        'RELATIONSHIP_HEALTH_SERVICE', 'SYSTEM', '{}'::jsonb, now(), now())
                """);
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO relationship_health_audit_outbox
                    (event_id, event_type, action, source_service, actor_id, payload,
                     created_at, next_attempt_at)
                VALUES (repeat('a', 64), 'RELATIONSHIP_HEALTH', 'TASK_CREATED',
                        'RELATIONSHIP_HEALTH_SERVICE', 'SYSTEM', '{}'::jsonb, now(), now())
                """));
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO relationship_health_audit_outbox
                    (event_id, event_type, action, source_service, actor_id, payload,
                     created_at, next_attempt_at)
                VALUES ('bad-id', 'RELATIONSHIP_HEALTH', 'TASK_CREATED',
                        'RELATIONSHIP_HEALTH_SERVICE', 'SYSTEM', '{}'::jsonb, now(), now())
                """));
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO relationship_health_audit_outbox
                    (event_id, event_type, action, source_service, actor_id, payload,
                     created_at, next_attempt_at)
                VALUES (repeat('b', 64), 'RELATIONSHIP_HEALTH', 'UNKNOWN_ACTION',
                        'RELATIONSHIP_HEALTH_SERVICE', 'SYSTEM', '{}'::jsonb, now(), now())
                """));
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO relationship_health_audit_outbox
                    (event_id, event_type, action, source_service, actor_id, payload,
                     created_at, next_attempt_at, lease_token)
                VALUES (repeat('c', 64), 'RELATIONSHIP_HEALTH', 'TASK_CREATED',
                        'RELATIONSHIP_HEALTH_SERVICE', 'SYSTEM', '{}'::jsonb,
                        now(), now(), 'lease-without-expiry')
                """));
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO relationship_health_audit_outbox
                    (event_id, event_type, action, source_service, actor_id, payload,
                     created_at, next_attempt_at)
                VALUES (repeat('d', 64), 'RELATIONSHIP_HEALTH', 'TASK_CREATED',
                        'RELATIONSHIP_HEALTH_SERVICE', 'SYSTEM', '[]'::jsonb, now(), now())
                """));
    }

    private void insertTask(String taskId, String clientId, String status, long version)
            throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO relationship_tasks
                            (task_id, client_id, status, severity, version, created_at, updated_at)
                        VALUES (?, ?, ?, 'HIGH', ?, ?, ?)
                        """)) {
            Instant now = Instant.parse("2026-10-21T10:00:00Z");
            statement.setString(1, taskId);
            statement.setString(2, clientId);
            statement.setString(3, status);
            statement.setLong(4, version);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setTimestamp(6, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private void insertEvidence(String taskId, String clientId, String fingerprint)
            throws SQLException {
        insertEvidence(
                taskId,
                clientId,
                fingerprint,
                "SUSTAINED_NET_OUTFLOW",
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"));
    }

    private void insertEvidence(
            String taskId,
            String clientId,
            String fingerprint,
            String signalType,
            Instant baselineFrom,
            Instant baselineTo,
            Instant comparisonFrom,
            Instant comparisonTo
    ) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO relationship_task_evidence
                            (task_id, client_id, finding_id, analysis_run_id, ruleset_version,
                             correlation_id, finding_detected_at, attached_at, signal_id,
                             account_id, signal_type, rule_version, severity, baseline_from,
                             baseline_to, comparison_from, comparison_to, evidence_payload,
                             fingerprint)
                        VALUES (?, ?, 'finding-1', 'run-1', 'rules-1', 'correlation-1',
                                now(), now(), 'signal-1', 'account-1', ?, 'rule-1', 'HIGH',
                                ?, ?, ?, ?, '{}'::jsonb, ?)
                        """)) {
            statement.setString(1, taskId);
            statement.setString(2, clientId);
            statement.setString(3, signalType);
            statement.setTimestamp(4, Timestamp.from(baselineFrom));
            statement.setTimestamp(5, Timestamp.from(baselineTo));
            statement.setTimestamp(6, Timestamp.from(comparisonFrom));
            statement.setTimestamp(7, Timestamp.from(comparisonTo));
            statement.setString(8, fingerprint);
            statement.executeUpdate();
        }
    }

    private void insertHistory(
            String taskId,
            long version,
            String action,
            String before,
            String after,
            String note) throws SQLException {
        insertHistory(taskId, version, action, before, after, note, null);
    }

    private void insertHistory(
            String taskId,
            long version,
            String action,
            String before,
            String after,
            String note,
            String dispositionCode) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO relationship_task_history
                            (task_id, task_version, action, actor_subject, correlation_id,
                             occurred_at, status_before, status_after, severity_before,
                             severity_after, note, disposition_code)
                        VALUES (?, ?, ?, 'agent-1', 'correlation-1', now(), ?, ?,
                                CASE WHEN ? IS NULL THEN NULL ELSE 'HIGH' END, 'HIGH', ?, ?)
                        """)) {
            statement.setString(1, taskId);
            statement.setLong(2, version);
            statement.setString(3, action);
            statement.setString(4, before);
            statement.setString(5, after);
            statement.setString(6, before);
            statement.setString(7, note);
            statement.setString(8, dispositionCode);
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private Connection connection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
