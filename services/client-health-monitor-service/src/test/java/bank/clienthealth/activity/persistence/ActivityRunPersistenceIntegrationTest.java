package bank.clienthealth.activity.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank.clienthealth.activity.application.ActivityProjectionService;
import bank.clienthealth.activity.application.ActivityProjectionService.ProjectionConflictException;
import bank.clienthealth.analysis.persistence.JdbcAnalysisRunRepository;
import bank.clienthealth.analysis.persistence.JdbcAnalysisRunRepository.LeaseConflictException;
import bank.clienthealth.analysis.port.AnalysisRunPort.AcquisitionStatus;
import bank.clienthealth.analysis.port.AnalysisRunPort.FailureCategory;
import bank.clienthealth.audit.JdbcAuditOutboxRepository;
import bank.clienthealth.transaction.application.TransactionReplayService.ReplayResult;
import bank.clienthealth.transaction.contract.MatchedTransaction;
import bank.clienthealth.transaction.contract.MatchedTransaction.ActivityKind;
import bank.clienthealth.transaction.contract.MatchedTransaction.Status;
import bank.clienthealth.transaction.contract.MatchedTransaction.TransactionType;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
class ActivityRunPersistenceIntegrationTest {
    private static final Instant FROM = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-01T00:05:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private JdbcActivityProjectionRepository activityRepository;
    private JdbcAnalysisRunRepository runRepository;

    @BeforeEach
    void migrateAndClear() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE relationship_health_audit_outbox,
                    relationship_health_analysis_runs,
                    client_health_replay_coverage,
                    client_health_activity_projection
                RESTART IDENTITY
                """);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        activityRepository = new JdbcActivityProjectionRepository(jdbc, transactions);
        AtomicInteger leaseSequence = new AtomicInteger();
        var auditOutbox = new JdbcAuditOutboxRepository(
                jdbc, transactions,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                () -> "audit-lease-" + leaseSequence.incrementAndGet());
        runRepository = new JdbcAnalysisRunRepository(
                jdbc, transactions, () -> "run-lease-" + leaseSequence.incrementAndGet(),
                auditOutbox);
    }

    @Test
    void projectionIsIdempotentUpdatesPendingAndRollsBackConflictingReplay() {
        var service = new ActivityProjectionService(activityRepository);
        var pending = transaction("txn-1", Status.PENDING, "50.00");

        assertEquals(new ActivityProjectionService.ApplyResult(1, 0, 0),
                service.apply(replay(pending)));
        assertEquals(new ActivityProjectionService.ApplyResult(0, 0, 1),
                service.apply(replay(pending)));
        assertEquals(new ActivityProjectionService.ApplyResult(0, 1, 0),
                service.apply(replay(transaction("txn-1", Status.COMPLETED, "50.00"))));
        boolean covered = activityRepository.inTransaction(
                session -> session.covers(FROM, TO));
        assertTrue(covered);
        assertEquals(Status.COMPLETED, activityRepository.inTransaction(
                session -> session.findById("txn-1").orElseThrow().status()));

        ReplayResult conflict = replay(
                transaction("txn-new", Status.COMPLETED, "10.00"),
                transaction("txn-1", Status.COMPLETED, "75.00"));
        assertThrows(ProjectionConflictException.class, () -> service.apply(conflict));
        boolean rolledBack = activityRepository.inTransaction(
                session -> session.findById("txn-new").isEmpty());
        assertTrue(rolledBack);
    }

    @Test
    void analysisLeaseCompletesOnceAndPersistsWatermark() {
        var acquired = runRepository.acquire(
                "run-1", TO, NOW, Duration.ofMinutes(60));
        var held = runRepository.acquire(
                "run-1", TO, NOW.plusSeconds(1), Duration.ofMinutes(60));

        assertEquals(AcquisitionStatus.ACQUIRED, acquired.status());
        assertEquals(AcquisitionStatus.LEASE_HELD, held.status());

        var renewed = runRepository.renew(
                acquired.lease(), NOW.plusSeconds(30), Duration.ofMinutes(60));
        runRepository.succeed(renewed, TO, NOW.plusSeconds(60));

        assertEquals(AcquisitionStatus.ALREADY_SUCCEEDED,
                runRepository.acquire("run-1", TO, NOW.plusSeconds(120),
                        Duration.ofMinutes(60)).status());
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT status FROM relationship_health_analysis_runs WHERE run_id = 'run-1'",
                String.class));
        assertEquals(TO, jdbc.queryForObject(
                "SELECT watermark FROM relationship_health_analysis_runs WHERE run_id = 'run-1'",
                java.time.OffsetDateTime.class).toInstant());
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM relationship_health_audit_outbox
                WHERE action IN ('ANALYSIS_RUN_STARTED', 'ANALYSIS_RUN_COMPLETED')
                """, Integer.class));
    }

    @Test
    void expiredLeaseIsRecoveredWithFencingAndFailureLeavesNoWatermark() {
        var first = runRepository.acquire(
                "run-retry", TO, NOW, Duration.ofMinutes(1));
        var recovered = runRepository.acquire(
                "run-retry", TO, NOW.plus(Duration.ofMinutes(2)), Duration.ofMinutes(60));

        assertEquals(AcquisitionStatus.ACQUIRED, recovered.status());
        assertNotEquals(first.lease().token(), recovered.lease().token());
        assertThrows(LeaseConflictException.class, () -> runRepository.renew(
                first.lease(), NOW.plus(Duration.ofMinutes(2)), Duration.ofMinutes(60)));

        runRepository.fail(
                recovered.lease(), FailureCategory.TASK_HANDOFF,
                NOW.plus(Duration.ofMinutes(3)));

        assertEquals(2, jdbc.queryForObject(
                "SELECT attempts FROM relationship_health_analysis_runs WHERE run_id = 'run-retry'",
                Integer.class));
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT status FROM relationship_health_analysis_runs WHERE run_id = 'run-retry'",
                String.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM relationship_health_analysis_runs
                WHERE run_id = 'run-retry' AND watermark IS NOT NULL
                """, Integer.class));
        assertEquals(3, jdbc.queryForObject("""
                SELECT count(*) FROM relationship_health_audit_outbox
                WHERE action IN ('ANALYSIS_RUN_STARTED', 'ANALYSIS_RUN_FAILED')
                """, Integer.class));
    }

    private static ReplayResult replay(MatchedTransaction... transactions) {
        return new ReplayResult(FROM, TO, List.of(transactions), 1);
    }

    private static MatchedTransaction transaction(
            String id, Status status, String amount
    ) {
        return new MatchedTransaction(
                id, "client-1", "account-1", TransactionType.WITHDRAWAL,
                new BigDecimal(amount), "SGD", FROM.plusSeconds(60),
                status, ActivityKind.CUSTOMER_INITIATED, null);
    }
}
