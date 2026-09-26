package bank.alert.repository;

import bank.alert.domain.Alert;
import bank.alert.domain.AlertDecision;
import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertStatus;
import bank.alert.domain.AuditOutbox;
import bank.alert.domain.HoldingSnapshot;
import bank.alert.domain.ValuationFreshness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AlertRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private HoldingSnapshotRepository holdingRepository;

    @Autowired
    private AlertDecisionRepository decisionRepository;

    @Autowired
    private AuditOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedReferencedSyntheticData() {
        jdbcTemplate.update("""
                INSERT INTO search_run
                    (id, window_from, window_to, status, created_at)
                VALUES
                    ('10000000-0000-4000-8000-000000000010',
                     '2026-09-26T23:00:00Z', '2026-09-27T00:00:00Z',
                     'SUCCEEDED', '2026-09-27T00:00:00Z')
                """);
        jdbcTemplate.update("""
                INSERT INTO source_result
                    (id, search_run_id, canonical_url, canonical_url_hash, title,
                     source_domain, retrieved_at, expires_at)
                VALUES
                    ('10000000-0000-4000-8000-000000000011',
                     '10000000-0000-4000-8000-000000000010',
                     'https://example.invalid/synthetic-event',
                     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                     'Synthetic market event', 'example.invalid',
                     '2026-09-27T00:00:00Z', '2026-12-26T00:00:00Z')
                """);
        jdbcTemplate.update("""
                INSERT INTO public_event
                    (id, source_result_id, classification, direction, confidence,
                     classifier_version, occurred_at, created_at)
                VALUES
                    ('10000000-0000-4000-8000-000000000001',
                     '10000000-0000-4000-8000-000000000011',
                     'CORPORATE_ACTION', 'NEGATIVE', 0.9000,
                     'fixture-v1', '2026-09-26T23:30:00Z', '2026-09-27T00:00:00Z')
                """);
        jdbcTemplate.update("""
                INSERT INTO instrument
                    (id, ticker, issuer_name, sector, currency)
                VALUES
                    ('SYN-SGX-001', 'SYN1', 'Synthetic Issuer One', 'Synthetic', 'SGD')
                """);
        jdbcTemplate.update("""
                INSERT INTO synthetic_portfolio
                    (client_ref, account_ref, assigned_agent_subject, source_version)
                VALUES
                    ('syn-client-001', 'syn-account-001', 'syn-agent-001',
                     'synthetic-holdings-v1')
                """);
    }

    @Test
    void persistsAlertAndEnforcesAgentScope() {
        HoldingSnapshot holding = holdingRepository.save(snapshot());
        Alert alert = alertRepository.saveAndFlush(alert(holding.getId()));

        assertThat(alertRepository.findByIdAndAssignedAgentSubject(alert.getId(), "syn-agent-001"))
                .isPresent();
        assertThat(alertRepository.findByIdAndAssignedAgentSubject(alert.getId(), "syn-agent-999"))
                .isEmpty();
    }

    @Test
    void rejectsDuplicateClientEventInstrumentAlert() {
        HoldingSnapshot holding = holdingRepository.save(snapshot());
        Alert first = alert(holding.getId());
        alertRepository.saveAndFlush(first);

        Alert duplicate = new Alert(
                UUID.randomUUID(),
                first.getClientRef(),
                first.getAssignedAgentSubject(),
                first.getPublicEventId(),
                first.getInstrumentId(),
                holding.getId(),
                new BigDecimal("75.00"),
                "materiality-v1",
                Instant.parse("2026-09-27T00:02:00Z"));

        assertThatThrownBy(() -> alertRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storesDecisionIdempotencyAndJsonAuditPayload() {
        HoldingSnapshot holding = holdingRepository.save(snapshot());
        Alert alert = alertRepository.saveAndFlush(alert(holding.getId()));
        AlertDecision decision = new AlertDecision(
                UUID.randomUUID(), alert.getId(), "syn-agent-001", AlertDecisionType.MONITOR,
                AlertStatus.NEW, AlertStatus.MONITORING, null, "decision-key-001",
                Instant.parse("2026-09-27T00:10:00Z"), 1L);
        decisionRepository.saveAndFlush(decision);
        outboxRepository.saveAndFlush(new AuditOutbox(
                UUID.randomUUID(), "alert.decision-recorded", 1,
                "{\"alertId\":\"" + alert.getId() + "\"}",
                Instant.parse("2026-09-27T00:10:00Z")));

        assertThat(decisionRepository.findByActorSubjectAndIdempotencyKey(
                "syn-agent-001", "decision-key-001"))
                .map(AlertDecision::getId)
                .contains(decision.getId());
        assertThat(decision.getAlertVersion()).isEqualTo(1L);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    private static HoldingSnapshot snapshot() {
        return new HoldingSnapshot(
                UUID.randomUUID(), "syn-client-001", "syn-account-001", "syn-agent-001",
                "SYN-SGX-001", new BigDecimal("100.00000000"), new BigDecimal("25.00000000"),
                "SGD", BigDecimal.ONE, new BigDecimal("2500.0000"),
                Instant.parse("2026-09-26T23:00:00Z"), ValuationFreshness.FRESH,
                "synthetic-holdings-v1");
    }

    private static Alert alert(UUID snapshotId) {
        return new Alert(
                UUID.randomUUID(), "syn-client-001", "syn-agent-001",
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                "SYN-SGX-001", snapshotId, new BigDecimal("72.50"),
                "materiality-v1", Instant.parse("2026-09-27T00:01:00Z"));
    }
}
