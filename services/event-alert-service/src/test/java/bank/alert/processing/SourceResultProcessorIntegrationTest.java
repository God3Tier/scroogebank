package bank.alert.processing;

import bank.alert.ai.AffectedInstrument;
import bank.alert.ai.AgentBriefing;
import bank.alert.ai.BriefingOutcome;
import bank.alert.ai.BriefingStatus;
import bank.alert.ai.ClassificationOutcome;
import bank.alert.ai.ClassificationStatus;
import bank.alert.ai.EventClassification;
import bank.alert.ai.EventDirection;
import bank.alert.ai.EventType;
import bank.alert.audit.AuditOutboxLeaseService;
import bank.alert.domain.AlertStatus;
import bank.alert.domain.AuditOutbox;
import bank.alert.domain.SourceResult;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.holdings.SyntheticHoldingsProvider;
import bank.alert.holdings.ValuationFreshnessPolicy;
import bank.alert.repository.AlertListRepository;
import bank.alert.repository.AlertRepository;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.repository.HoldingSnapshotRepository;
import bank.alert.repository.SourceResultRepository;
import bank.alert.retention.RetentionCleanupOutcome;
import bank.alert.retention.RetentionCleanupService;
import bank.alert.scoring.ExposureCalculator;
import bank.alert.scoring.MaterialityScorer;
import bank.alert.service.AuditUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(AlertListRepository.class)
class SourceResultProcessorIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:30:00Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired SourceResultRepository sourceResults;
    @Autowired HoldingSnapshotRepository holdings;
    @Autowired AlertRepository alerts;
    @Autowired AuditOutboxRepository outbox;
    @Autowired AlertListRepository alertList;
    @Autowired JdbcTemplate jdbc;

    private ObjectMapper mapper;
    private Clock clock;

    @BeforeEach
    void seedReferences() {
        mapper = new ObjectMapper().findAndRegisterModules();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO search_run (id, window_from, window_to, status, created_at)
                VALUES ('70000000-0000-4000-8000-000000000001',
                        '2026-09-26T20:00:00Z', '2026-09-27T00:00:00Z',
                        'SUCCEEDED', '2026-09-27T00:00:00Z')
                """);
        jdbc.update("""
                INSERT INTO instrument (id, ticker, issuer_name, sector, currency)
                VALUES ('SYN-SGX-001', 'SYN1', 'Synthetic Issuer One', 'Synthetic', 'SGD')
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO synthetic_portfolio
                    (client_ref, account_ref, assigned_agent_subject, source_version)
                VALUES
                    ('syn-client-001', 'syn-account-001', 'syn-agent-001', 'synthetic-holdings-v1'),
                    ('syn-client-002', 'syn-account-002', 'syn-agent-002', 'synthetic-holdings-v1')
                ON CONFLICT (client_ref) DO NOTHING
                """);
    }

    @Test
    void processesStoredEvidenceIntoScopedAuditedAlertAndReplaysIdempotently() {
        SourceResult source = source();
        sourceResults.saveAndFlush(source);
        SourceResultProcessor processor = processor(100);

        SourceProcessingOutcome first = processor.process(request(source.getId(), "syn-agent-001"));
        SourceProcessingOutcome replay = processor.process(request(source.getId(), "syn-agent-001"));

        assertThat(first.classificationStatus()).isEqualTo(ClassificationStatus.MODEL_ACCEPTED);
        assertThat(first.briefingStatus()).isEqualTo(BriefingStatus.GENERATED);
        assertThat(first.generation().alertsCreated()).isOne();
        assertThat(replay.generation().alertsCreated()).isZero();
        assertThat(replay.generation().duplicates()).isOne();
        assertThat(alerts.count()).isOne();
        assertThat(outbox.findAll())
                .extracting(event -> event.getEventType())
                .containsExactlyInAnyOrder(
                        "alert.classification-recorded",
                        "alert.briefing-generated",
                        "alert.generated");
        assertThat(outbox.findClaimable(NOW, 2, 10)).hasSize(2);

        UUID alertId = first.generation().alertIds().getFirst();
        assertThat(alertList.findVisibleDetail(alertId, "syn-agent-001", false))
                .hasValueSatisfying(detail -> {
                    assertThat(detail.summary().status()).isEqualTo(AlertStatus.NEW);
                    assertThat(detail.summary().score()).isEqualByComparingTo("92.00");
                    assertThat(detail.briefing().summary()).contains("synthetic earnings");
                });
        assertThat(alertList.findVisibleDetail(alertId, "syn-agent-999", false)).isEmpty();
        assertThat(alertList.findFactors(alertId)).hasSize(4);
    }

    @Test
    void failsClosedBeforePersistenceWhenAuditCapacityIsUnavailable() {
        SourceResult source = source();
        sourceResults.saveAndFlush(source);

        assertThatThrownBy(() -> processor(0).process(request(source.getId(), "syn-agent-001")))
                .isInstanceOf(AuditUnavailableException.class)
                .hasMessageContaining("capacity");

        assertThat(alerts.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_event", Long.class)).isZero();
    }

    @Test
    void boundedRetentionRemovesExpiredContentButPreservesAuditEvidence() {
        SourceResult source = source();
        sourceResults.saveAndFlush(source);
        SourceProcessingOutcome processed = processor(100)
                .process(request(source.getId(), "syn-agent-001"));
        long auditEvidenceBeforeCleanup = outbox.count();
        assertThat(auditEvidenceBeforeCleanup).isEqualTo(3L);
        jdbc.update("UPDATE source_result SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)), source.getId());
        jdbc.update("UPDATE briefing SET expires_at = ? WHERE alert_id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)),
                processed.generation().alertIds().getFirst());

        RetentionCleanupOutcome outcome = new RetentionCleanupService(jdbc, clock, 10)
                .cleanExpired();

        assertThat(outcome.briefingsDeleted()).isOne();
        assertThat(outcome.sourcesRedacted()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM briefing", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT title FROM source_result WHERE id = ?", String.class, source.getId()))
                .isEqualTo("[expired]");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox", Long.class))
                .isEqualTo(auditEvidenceBeforeCleanup);
    }

    @Test
    void auditLeasesRecoverAfterExpiryAndStopAtTheRetryCap() {
        UUID eventId = UUID.fromString("70000000-0000-4000-8000-000000000099");
        outbox.saveAndFlush(new AuditOutbox(eventId, "alert.generated", 1,
                "{\"alertId\":\"synthetic\"}", NOW.minusSeconds(120)));
        AuditOutboxLeaseService leases = new AuditOutboxLeaseService(outbox);

        assertThat(leases.claim(NOW.minusSeconds(60), 10, Duration.ofSeconds(30), 2))
                .singleElement().satisfies(event -> {
                    assertThat(event.getStatus()).isEqualTo("LEASED");
                    assertThat(event.getAttemptCount()).isOne();
                });
        outbox.flush();

        assertThat(leases.claim(NOW, 10, Duration.ofSeconds(30), 2))
                .singleElement().satisfies(event -> {
                    assertThat(event.getEventId()).isEqualTo(eventId);
                    assertThat(event.getAttemptCount()).isEqualTo(2);
                });
        leases.failed(eventId);
        outbox.flush();

        assertThat(leases.claim(NOW.plusSeconds(60), 10, Duration.ofSeconds(30), 2))
                .isEmpty();
        assertThat(outbox.findById(eventId)).hasValueSatisfying(event -> {
            assertThat(event.getStatus()).isEqualTo("FAILED");
            assertThat(event.getAttemptCount()).isEqualTo(2);
        });
    }

    private SourceResultProcessor processor(int auditCapacity) {
        SyntheticHoldingsProvider provider = new SyntheticHoldingsProvider(
                new SyntheticFixtureLoader(mapper), new ValuationFreshnessPolicy());
        AlertGenerationService generation = new AlertGenerationService(
                sourceResults, provider, new ExposureCalculator(), new MaterialityScorer(),
                holdings, alerts, outbox, jdbc, mapper, clock, auditCapacity);
        return new SourceResultProcessor(sourceResults,
                evidence -> new ClassificationOutcome(
                        ClassificationStatus.MODEL_ACCEPTED,
                        new EventClassification("Synthetic earnings event", EventType.EARNINGS,
                                EventDirection.POSITIVE, new BigDecimal("0.90"),
                                List.of(new AffectedInstrument("SYN-SGX-001", evidence.referenceId())),
                                List.of(evidence.referenceId()), "fixture-model-v1", "prompt-v1", NOW),
                        null, 1),
                input -> new BriefingOutcome(BriefingStatus.GENERATED,
                        new AgentBriefing("Synthetic issuer update",
                                "The issuer reported synthetic earnings for review.",
                                "The fixture is intentionally synthetic.",
                                input.evidence().stream().map(evidence -> evidence.referenceId()).toList(),
                                List.of("Review the cited source and deterministic factors."), true,
                                "fixture-model-v1", "prompt-v1", NOW), null, 1),
                generation);
    }

    private static SourceProcessingRequest request(UUID sourceId, String agent) {
        return new SourceProcessingRequest(sourceId,
                "Synthetic earnings increased after a public issuer filing.",
                List.of("SYN-SGX-001"), agent, new BigDecimal("0.90"),
                new BigDecimal("0.80"), NOW);
    }

    private static SourceResult source() {
        return new SourceResult(
                UUID.fromString("70000000-0000-4000-8000-000000000002"),
                UUID.fromString("70000000-0000-4000-8000-000000000001"),
                "fixture-result-1", "https://example.invalid/synthetic-earnings",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "Synthetic issuer earnings update", "example.invalid",
                Instant.parse("2026-09-27T00:00:00Z"), NOW,
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                Instant.parse("2026-12-26T00:30:00Z"));
    }
}
