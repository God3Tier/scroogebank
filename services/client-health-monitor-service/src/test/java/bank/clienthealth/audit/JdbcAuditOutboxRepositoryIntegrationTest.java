package bank.clienthealth.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank.clienthealth.audit.AuditOutboxRepository.FailureCategory;
import bank.clienthealth.audit.JdbcAuditOutboxRepository.OutboxConflictException;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.Action;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
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
class JdbcAuditOutboxRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private JdbcAuditOutboxRepository repository;

    @BeforeEach
    void migrateAndClear() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE relationship_health_audit_outbox");
        AtomicInteger leases = new AtomicInteger();
        repository = new JdbcAuditOutboxRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                () -> "lease-" + leases.incrementAndGet());
    }

    @Test
    void enqueueIsIdempotentButRejectsEventIdReuseWithDifferentContent() {
        var event = event("run-1", Action.ANALYSIS_RUN_STARTED, NOW);
        repository.enqueue(event);
        repository.enqueue(event);

        assertEquals(1, count());

        var conflicting = new RelationshipHealthAuditEvent(
                event.eventId(), Action.ANALYSIS_RUN_FAILED, event.actorId(), event.payload());
        assertThrows(OutboxConflictException.class, () -> repository.enqueue(conflicting));
        assertEquals(1, count());
    }

    @Test
    void leasesDeliversReleasesAndRetriesWithFencing() {
        var first = event("run-1", Action.ANALYSIS_RUN_STARTED, NOW);
        var second = event("run-2", Action.ANALYSIS_RUN_COMPLETED, NOW);
        repository.enqueue(first);
        repository.enqueue(second);

        var leased = repository.leaseBatch(NOW, 10, Duration.ofSeconds(30));
        assertEquals(2, leased.size());
        var firstLease = leased.stream()
                .filter(item -> item.event().eventId().equals(first.eventId()))
                .findFirst().orElseThrow();
        var secondLease = leased.stream()
                .filter(item -> item.event().eventId().equals(second.eventId()))
                .findFirst().orElseThrow();

        repository.release(
                first.eventId(), firstLease.leaseToken(), NOW.plusSeconds(10),
                FailureCategory.DELIVERY_UNAVAILABLE);
        repository.markDelivered(second.eventId(), secondLease.leaseToken());
        assertThrows(OutboxConflictException.class, () ->
                repository.markDelivered(first.eventId(), firstLease.leaseToken()));
        assertTrue(repository.leaseBatch(
                NOW.plusSeconds(9), 10, Duration.ofSeconds(30)).isEmpty());

        var retry = repository.leaseBatch(
                NOW.plusSeconds(10), 10, Duration.ofSeconds(30)).getFirst();
        assertEquals(2, retry.attemptCount());
        assertNotEquals(firstLease.leaseToken(), retry.leaseToken());
        repository.markDelivered(first.eventId(), retry.leaseToken());
        assertEquals(0, count());
    }

    @Test
    void recoversAnExpiredLeaseWithANewFencingToken() {
        var event = event("run-expired", Action.ANALYSIS_RUN_FAILED, NOW);
        repository.enqueue(event);
        var first = repository.leaseBatch(
                NOW, 1, Duration.ofSeconds(1)).getFirst();

        var recovered = repository.leaseBatch(
                NOW.plusSeconds(1), 1, Duration.ofSeconds(30)).getFirst();

        assertEquals(2, recovered.attemptCount());
        assertNotEquals(first.leaseToken(), recovered.leaseToken());
        assertThrows(OutboxConflictException.class, () ->
                repository.markDelivered(event.eventId(), first.leaseToken()));
    }

    private int count() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM relationship_health_audit_outbox", Integer.class);
    }

    private static RelationshipHealthAuditEvent event(
            String runId, Action action, Instant occurredAt
    ) {
        return RelationshipHealthAuditEventFactory.analysis(
                runId, action, occurredAt,
                action == Action.ANALYSIS_RUN_FAILED ? "UNEXPECTED" : null);
    }
}
