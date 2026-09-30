package bank.alert.service;

import bank.alert.api.DecisionRequest;
import bank.alert.api.FeedbackRequest;
import bank.alert.domain.Alert;
import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertStatus;
import bank.alert.domain.FeedbackRating;
import bank.alert.domain.HoldingSnapshot;
import bank.alert.domain.ValuationFreshness;
import bank.alert.repository.AlertDecisionRepository;
import bank.alert.repository.AlertFeedbackRepository;
import bank.alert.repository.AlertRepository;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.repository.HoldingSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({AlertMutationService.class, AlertStateMachine.class, AlertMutationServiceTest.TestConfiguration.class})
@TestPropertySource(properties = "alert.audit.max-pending=100")
class AlertMutationServiceTest {

    private static final UUID ALERT_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AlertMutationService mutationService;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private HoldingSnapshotRepository holdingRepository;

    @Autowired
    private AlertDecisionRepository decisionRepository;

    @Autowired
    private AlertFeedbackRepository feedbackRepository;

    @Autowired
    private AuditOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedAlert() {
        jdbcTemplate.update("""
                INSERT INTO search_run (id, window_from, window_to, status, created_at)
                VALUES ('10000000-0000-4000-8000-000000000010',
                        '2026-09-26T23:00:00Z', '2026-09-27T00:00:00Z',
                        'SUCCEEDED', '2026-09-27T00:00:00Z')
                """);
        jdbcTemplate.update("""
                INSERT INTO source_result
                    (id, search_run_id, canonical_url, canonical_url_hash, title,
                     source_domain, retrieved_at, expires_at)
                VALUES ('10000000-0000-4000-8000-000000000011',
                        '10000000-0000-4000-8000-000000000010',
                        'https://example.invalid/api-test',
                        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        'Synthetic API event', 'example.invalid',
                        '2026-09-27T00:00:00Z', '2026-12-26T00:00:00Z')
                """);
        jdbcTemplate.update("""
                INSERT INTO public_event
                    (id, source_result_id, classification, direction, confidence,
                     classifier_version, occurred_at, created_at)
                VALUES ('10000000-0000-4000-8000-000000000001',
                        '10000000-0000-4000-8000-000000000011',
                        'REGULATORY', 'UNCERTAIN', 0.8200, 'fixture-v1',
                        '2026-09-26T23:30:00Z', '2026-09-27T00:00:00Z')
                """);
        jdbcTemplate.update("""
                INSERT INTO instrument (id, ticker, issuer_name, sector, currency)
                VALUES ('SYN-SGX-001', 'SYN1', 'Synthetic Issuer One', 'Synthetic', 'SGD')
                ON CONFLICT (id) DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO synthetic_portfolio
                    (client_ref, account_ref, assigned_agent_subject, source_version)
                VALUES ('syn-client-001', 'syn-account-001', 'syn-agent-001',
                        'synthetic-holdings-v1')
                """);
        HoldingSnapshot holding = holdingRepository.save(new HoldingSnapshot(
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                "syn-client-001", "syn-account-001", "syn-agent-001", "SYN-SGX-001",
                new BigDecimal("100"), new BigDecimal("25"), "SGD", BigDecimal.ONE,
                new BigDecimal("2500.0000"), Instant.parse("2026-09-26T23:00:00Z"),
                ValuationFreshness.FRESH, "synthetic-holdings-v1"));
        alertRepository.saveAndFlush(new Alert(
                ALERT_ID, "syn-client-001", "syn-agent-001",
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                "SYN-SGX-001", holding.getId(), new BigDecimal("72.50"),
                "materiality-v1", Instant.parse("2026-09-27T00:01:00Z")));
    }

    @Test
    void recordsDecisionAndRedactedAuditAtomicallyThenReplays() {
        DecisionRequest request = new DecisionRequest(AlertDecisionType.MONITOR, 0L, null);

        var first = mutationService.recordDecision(
                ALERT_ID, "syn-agent-001", "decision-key-001", UUID.randomUUID(), request);
        var replay = mutationService.recordDecision(
                ALERT_ID, "syn-agent-001", "decision-key-001", UUID.randomUUID(), request);

        assertThat(first.getId()).isEqualTo(replay.getId());
        assertThat(first.getAlertVersion()).isEqualTo(1L);
        assertThat(alertRepository.findById(ALERT_ID).orElseThrow().getStatus())
                .isEqualTo(AlertStatus.MONITORING);
        assertThat(decisionRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.findAll()).singleElement().satisfies(outbox -> {
            assertThat(outbox.getPayload()).contains("decisionId", "syn-agent-001");
            assertThat(outbox.getPayload()).doesNotContain("clientRef", "accountRef", "holding");
        });
    }

    @Test
    void hidesOtherAgentsAlertAndRejectsStaleVersion() {
        assertThatThrownBy(() -> mutationService.recordDecision(
                ALERT_ID, "syn-agent-999", "decision-key-999",
                UUID.randomUUID(),
                new DecisionRequest(AlertDecisionType.MONITOR, 0L, null)))
                .isInstanceOf(AlertNotFoundException.class);

        assertThatThrownBy(() -> mutationService.recordDecision(
                ALERT_ID, "syn-agent-001", "decision-key-002",
                UUID.randomUUID(),
                new DecisionRequest(AlertDecisionType.MONITOR, 4L, null)))
                .isInstanceOf(AlertMutationConflictException.class);
        assertThat(decisionRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void recordsIdempotentFeedbackWithoutChangingAlertVersion() {
        FeedbackRequest request = new FeedbackRequest(FeedbackRating.USEFUL, null, null, 0L);

        var first = mutationService.recordFeedback(
                ALERT_ID, "syn-agent-001", "feedback-key-001", UUID.randomUUID(), request);
        var replay = mutationService.recordFeedback(
                ALERT_ID, "syn-agent-001", "feedback-key-001", UUID.randomUUID(), request);

        assertThat(first.getId()).isEqualTo(replay.getId());
        assertThat(first.getAlertVersion()).isZero();
        assertThat(feedbackRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-27T00:10:00Z"), ZoneOffset.UTC);
        }
    }
}
