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
import bank.alert.config.AiProperties;
import bank.alert.config.PipelineProperties;
import bank.alert.domain.Alert;
import bank.alert.domain.SourceResult;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.holdings.SyntheticHoldingsProvider;
import bank.alert.holdings.ValuationFreshnessPolicy;
import bank.alert.matching.InstrumentMatcher;
import bank.alert.repository.AlertListRepository;
import bank.alert.repository.AlertRepository;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.repository.HoldingSnapshotRepository;
import bank.alert.repository.InstrumentRepository;
import bank.alert.repository.SourceResultRepository;
import bank.alert.scoring.EventSeverityPolicy;
import bank.alert.scoring.ExposureCalculator;
import bank.alert.scoring.MaterialityScorer;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole source-to-alert path on PostgreSQL with synthetic data: stored sources
 * are matched to instruments, classified once, and turned into alerts for each
 * agent who holds the instrument, each seeing only their own client's alert
 * (ADR-0049). The model is a fixture; no AWS and no network.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(AlertListRepository.class)
class SourcePipelineIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:30:00Z");
    private static final UUID RUN_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired SourceResultRepository sourceResults;
    @Autowired InstrumentRepository instruments;
    @Autowired HoldingSnapshotRepository holdings;
    @Autowired AlertRepository alerts;
    @Autowired AuditOutboxRepository outbox;
    @Autowired AlertListRepository alertList;
    @Autowired JdbcTemplate jdbc;

    private final AtomicReference<ClassificationOutcome> classification = new AtomicReference<>();
    private SourcePipeline pipeline;

    @BeforeEach
    void seed() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO search_run (id, window_from, window_to, status, created_at)
                VALUES (?, '2026-09-26T20:00:00Z', '2026-09-27T00:00:00Z', 'SUCCEEDED', '2026-09-27T00:00:00Z')
                """, RUN_ID);
        jdbc.update("""
                INSERT INTO synthetic_portfolio
                    (client_ref, account_ref, assigned_agent_subject, source_version)
                VALUES
                    ('syn-client-001', 'syn-account-001', 'syn-agent-001', 'synthetic-holdings-v1'),
                    ('syn-client-002', 'syn-account-002', 'syn-agent-002', 'synthetic-holdings-v1')
                ON CONFLICT (client_ref) DO NOTHING
                """);
        classification.set(accepted());

        SyntheticHoldingsProvider provider = new SyntheticHoldingsProvider(
                new SyntheticFixtureLoader(mapper), new ValuationFreshnessPolicy());
        AlertGenerationService generation = new AlertGenerationService(
                sourceResults, provider, new ExposureCalculator(), new MaterialityScorer(),
                holdings, alerts, outbox, jdbc, mapper, clock, 1000);
        SourceResultProcessor processor = new SourceResultProcessor(sourceResults,
                evidence -> classification.get(),
                input -> new BriefingOutcome(BriefingStatus.GENERATED,
                        new AgentBriefing("Synthetic issuer update", "The issuer reported synthetic earnings.",
                                "The fixture is intentionally synthetic.",
                                input.evidence().stream().map(evidence -> evidence.referenceId()).toList(),
                                List.of("Review the cited source."), true, "fixture-model-v1", "prompt-v1", NOW),
                        null, 1),
                generation, provider, new EventSeverityPolicy());
        AiProperties ai = new AiProperties(true, null, null, 0, 0, 0, 0, Duration.ofSeconds(20), null);
        pipeline = new SourcePipeline(sourceResults, instruments, new InstrumentMatcher(), processor, ai,
                new PipelineProperties(50), clock);
    }

    private static ClassificationOutcome accepted() {
        return new ClassificationOutcome(ClassificationStatus.MODEL_ACCEPTED,
                new EventClassification("Synthetic earnings event", EventType.EARNINGS, EventDirection.POSITIVE,
                        new BigDecimal("0.90"), List.of(new AffectedInstrument("SYN-SGX-001", "ref")),
                        List.of("ref"), "fixture-model-v1", "prompt-v1", NOW), null, 1);
    }

    private SourceResult store(String title, String excerpt, String hashChar) {
        return sourceResults.saveAndFlush(new SourceResult(UUID.randomUUID(), RUN_ID, null,
                "https://example.invalid/" + UUID.randomUUID(), hashChar.repeat(64), title, "example.invalid",
                Instant.parse("2026-09-27T00:00:00Z"), NOW, null, NOW.plus(Duration.ofDays(90)), excerpt));
    }

    @Test
    void globalSyntheticCatalogueAndPortfoliosAreSeededByFlyway() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM synthetic_portfolio", Long.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM instrument WHERE id <> 'SYN-SGX-001'", Long.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject("""
                SELECT search_aliases @> '["DBS"]'::jsonb
                FROM instrument WHERE id = 'SGX-D05'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT official_domains @> '["sgx.com", "dbs.com"]'::jsonb
                FROM instrument WHERE id = 'SGX-D05'
                """, Boolean.class)).isTrue();
    }

    @Test
    void turnsAMatchedSourceIntoOneAlertPerAgentAndLeavesAnUnmatchedOneAlone() {
        SourceResult matched = store("SYN1 posts synthetic earnings",
                "Synthetic Issuer One reported earnings after a public filing.", "a");
        SourceResult unmatched = store("Weather outlook for the week", "Light rain expected.", "b");

        PipelineSummary first = pipeline.processPending();

        assertThat(first.examined()).isEqualTo(2);
        assertThat(first.noMatch()).isOne();
        assertThat(first.processed()).isOne();
        assertThat(first.alertsCreated()).isEqualTo(2);
        assertThat(first.errors()).isZero();
        assertThat(alerts.findAll()).extracting(Alert::getAssignedAgentSubject)
                .containsExactlyInAnyOrder("syn-agent-001", "syn-agent-002");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_event", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM briefing", Long.class)).isEqualTo(2);
        assertThat(sourceResults.findById(matched.getId()).orElseThrow().getProcessingOutcome())
                .isEqualTo("ALERTS_CREATED");
        assertThat(sourceResults.findById(unmatched.getId()).orElseThrow().getProcessingOutcome())
                .isEqualTo("NO_MATCH");

        // Each agent sees only the alert for their own client.
        for (Alert alert : alerts.findAll()) {
            String owner = alert.getAssignedAgentSubject();
            String other = owner.equals("syn-agent-001") ? "syn-agent-002" : "syn-agent-001";
            assertThat(alertList.findVisibleDetail(alert.getId(), owner, false)).isPresent();
            assertThat(alertList.findVisibleDetail(alert.getId(), other, false)).isEmpty();
        }
    }

    @Test
    void runningAgainFindsNothingNewAndCreatesNoDuplicates() {
        store("SYN1 posts synthetic earnings", "Synthetic Issuer One reported earnings.", "a");
        pipeline.processPending();

        PipelineSummary again = pipeline.processPending();

        assertThat(again.examined()).isZero();
        assertThat(alerts.count()).isEqualTo(2);
    }

    @Test
    void aSourceTheModelCouldNotHandleStaysPendingAndIsRetriedLater() {
        SourceResult source = store("SYN1 posts synthetic earnings", "Synthetic Issuer One reported earnings.", "a");
        classification.set(new ClassificationOutcome(ClassificationStatus.ABSTAINED, null, "PROVIDER_FAILURE", 2));

        PipelineSummary outage = pipeline.processPending();

        assertThat(outage.deferred()).isOne();
        assertThat(alerts.count()).isZero();
        assertThat(sourceResults.findById(source.getId()).orElseThrow().getProcessedAt()).isNull();

        classification.set(accepted());
        PipelineSummary recovered = pipeline.processPending();

        assertThat(recovered.alertsCreated()).isEqualTo(2);
        assertThat(sourceResults.findById(source.getId()).orElseThrow().getProcessingOutcome())
                .isEqualTo("ALERTS_CREATED");
    }

    @Test
    void aDefiniteAbstentionFinishesTheSourceWithoutAlerts() {
        SourceResult source = store("SYN1 posts synthetic earnings", "Synthetic Issuer One reported earnings.", "a");
        classification.set(ClassificationOutcome.abstained("MODEL_ABSTAINED", 1));

        PipelineSummary summary = pipeline.processPending();

        assertThat(summary.processed()).isOne();
        assertThat(alerts.count()).isZero();
        assertThat(sourceResults.findById(source.getId()).orElseThrow().getProcessingOutcome())
                .isEqualTo("ABSTAINED");
    }

    @Test
    void anExpiredSourceIsNotProcessed() {
        sourceResults.saveAndFlush(new SourceResult(UUID.randomUUID(), RUN_ID, null,
                "https://example.invalid/old", "c".repeat(64), "SYN1 old news", "example.invalid",
                Instant.parse("2026-06-01T00:00:00Z"), NOW.minus(Duration.ofDays(100)), null,
                NOW.minusSeconds(1), "Synthetic Issuer One"));

        assertThat(pipeline.processPending().examined()).isZero();
    }
}
