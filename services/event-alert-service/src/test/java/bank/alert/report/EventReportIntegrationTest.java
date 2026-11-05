package bank.alert.report;

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
import bank.alert.crm.CrmDirectory;
import bank.alert.crm.CurrentOwnership;
import bank.alert.domain.SourceResult;
import bank.alert.event.AgentEventModels;
import bank.alert.event.AgentEventQueryService;
import bank.alert.service.AlertNotFoundException;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.holdings.SyntheticHoldingsProvider;
import bank.alert.holdings.ValuationFreshnessPolicy;
import bank.alert.matching.InstrumentMatcher;
import bank.alert.processing.AlertGenerationService;
import bank.alert.processing.SourcePipeline;
import bank.alert.processing.SourceResultProcessor;
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
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Several sources about one event become one event and one versioned report, on PostgreSQL with synthetic data
 * (ADR-0084). The model is a fixture; no AWS and no network.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(AlertListRepository.class)
class EventReportIntegrationTest {

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
    @Autowired JdbcTemplate jdbc;

    private final AtomicReference<EventDirection> direction = new AtomicReference<>(EventDirection.POSITIVE);
    private final AtomicReference<ReportOutcome> reportOutcome = new AtomicReference<>();
    private final List<ReportInput> reportRequests = new ArrayList<>();
    private SourcePipeline pipeline;
    private EventReportService reports;
    private AgentEventQueryService eventQueries;

    @BeforeEach
    void seed() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO search_run (id, window_from, window_to, status, created_at)
                VALUES (?, '2026-09-26T20:00:00Z', '2026-09-27T00:00:00Z', 'SUCCEEDED', '2026-09-27T00:00:00Z')
                """, RUN_ID);
        jdbc.update("""
                INSERT INTO synthetic_portfolio (client_ref, account_ref, assigned_agent_subject, source_version)
                VALUES ('syn-client-001', 'syn-account-001', 'syn-agent-001', 'synthetic-holdings-v1'),
                       ('syn-client-002', 'syn-account-002', 'syn-agent-002', 'synthetic-holdings-v1')
                ON CONFLICT (client_ref) DO NOTHING
                """);
        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.GENERATED, report("first"), null, 1));

        SyntheticHoldingsProvider provider = new SyntheticHoldingsProvider(
                new SyntheticFixtureLoader(mapper), new ValuationFreshnessPolicy());
        AlertGenerationService generation = new AlertGenerationService(sourceResults, provider, new ExposureCalculator(),
                new MaterialityScorer(), holdings, alerts, outbox, jdbc, mapper, clock, 1000);
        SourceResultProcessor processor = new SourceResultProcessor(sourceResults,
                evidence -> new ClassificationOutcome(ClassificationStatus.MODEL_ACCEPTED,
                        new EventClassification("Synthetic earnings event", EventType.EARNINGS, direction.get(),
                                new BigDecimal("0.90"), List.of(new AffectedInstrument("SYN-SGX-001", "ref")),
                                List.of("ref"), "fixture-model-v1", "prompt-v1", NOW), null, 1),
                input -> new BriefingOutcome(BriefingStatus.GENERATED,
                        new AgentBriefing("Synthetic issuer update", "The issuer reported synthetic earnings.",
                                "Synthetic.", input.evidence().stream().map(e -> e.referenceId()).toList(),
                                List.of("Review."), true, "fixture-model-v1", "prompt-v1", NOW), null, 1),
                generation, provider, new EventSeverityPolicy());
        ClientReportGenerator generator = input -> {
            reportRequests.add(input);
            ReportOutcome outcome = reportOutcome.get();
            return outcome.status() == ReportOutcome.Status.GENERATED
                    ? new ReportOutcome(ReportOutcome.Status.GENERATED, report("v" + reportRequests.size()), null, 1,
                            "fixture-model-v1", "alert-client-report-v2", NOW)
                    : outcome;
        };
        reports = new EventReportService(jdbc, instruments, generator, outbox, mapper, clock, 1000);
        @SuppressWarnings("unchecked")
        ObjectProvider<CrmDirectory> noDirectory = mock(ObjectProvider.class);
        when(noDirectory.getIfAvailable()).thenReturn(null);
        eventQueries = new AgentEventQueryService(jdbc, new CurrentOwnership(jdbc, noDirectory, clock, outbox, mapper, 1000));
        AiProperties ai = new AiProperties(true, null, null, 0, 0, 0, 0, Duration.ofSeconds(20), null);
        pipeline = new SourcePipeline(sourceResults, instruments, new InstrumentMatcher(), processor, ai,
                new PipelineProperties(50), clock, reports);
    }

    private static ClientReport report(String headline) {
        return new ClientReport(headline,
                List.of(new ClientReport.CitedText("One.", List.of("a")), new ClientReport.CitedText("Two.", List.of("a"))),
                List.of(), List.of(new ClientReport.Implication("Why.", ClientReport.Kind.FACT, List.of("a"))), null,
                List.of("Watch one", "Watch two"), "Uncertain.", List.of("a"));
    }

    private SourceResult store(String domain, String publishedAt, String hash) {
        return sourceResults.saveAndFlush(new SourceResult(UUID.randomUUID(), RUN_ID, null,
                "https://" + domain + "/" + UUID.randomUUID(), hash.repeat(64), "SYN1 posts synthetic earnings", domain,
                Instant.parse(publishedAt), NOW, null, NOW.plus(Duration.ofDays(90)),
                "Synthetic Issuer One reported earnings after a public filing."));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private List<String> reportSources(int version) {
        String json = jdbc.queryForObject("SELECT source_ids::text FROM event_report WHERE version = ?", String.class, version);
        return java.util.Arrays.stream(json.replaceAll("[\\[\\]\" ]", "").split(",")).toList();
    }

    @Test
    void aSourceAboutTheSameEventFromAnotherDomainJoinsTheEventAndMakesTheNextReportVersion() {
        SourceResult first = store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();
        assertThat(count("public_event")).isOne();
        assertThat(count("event_report")).isOne();
        assertThat(reportSources(1)).containsExactly(first.getId().toString());
        long alertsAfterFirst = alerts.count();

        SourceResult second = store("two.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();

        assertThat(count("public_event")).as("one event, not two").isOne();
        assertThat(count("public_event_source")).isEqualTo(2);
        assertThat(alerts.count()).as("a corroborating source adds no duplicate alert").isEqualTo(alertsAfterFirst);
        assertThat(count("event_report")).isEqualTo(2);
        assertThat(reportSources(2)).containsExactly(first.getId().toString(), second.getId().toString());
        assertThat(reportRequests.get(1).evidence()).hasSize(2);
    }

    @Test
    void theFirstReportDoesNotWaitForMoreSourcesAndIsWrittenOncePerEvent() {
        store("one.example", "2026-09-26T22:00:00Z", "a");

        pipeline.processPending();
        pipeline.processPending();

        assertThat(reportRequests).hasSize(1);
        assertThat(count("event_report")).isOne();
    }

    @Test
    void aLaterSourceFromTheSameDomainDoesNotChangeTheChosenSourcesSoThereIsNoNewVersion() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();

        store("one.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();

        assertThat(count("public_event_source")).isEqualTo(2);
        assertThat(count("event_report")).isOne();
        assertThat(reportRequests).hasSize(1);
    }

    @Test
    void aConflictingDirectionStaysASeparateEvent() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();

        direction.set(EventDirection.NEGATIVE);
        store("two.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();

        assertThat(count("public_event")).isEqualTo(2);
        assertThat(count("event_report")).isEqualTo(2);
    }

    @Test
    void aSourceMoreThanSeventyTwoHoursApartIsAnotherEvent() {
        store("one.example", "2026-09-22T22:00:00Z", "a");
        pipeline.processPending();

        store("two.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();

        assertThat(count("public_event")).isEqualTo(2);
    }

    @Test
    void ambiguousMatchesAreNotMerged() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();
        // A second event of the same kind exists, so a new source cannot tell which one it belongs to.
        jdbc.update("""
                INSERT INTO public_event (id, source_result_id, classification, direction, confidence, classifier_version,
                                          model_version, prompt_version, occurred_at, created_at)
                SELECT gen_random_uuid(), source_result_id, classification, direction, confidence, 'twin-v1',
                       model_version, prompt_version, occurred_at, created_at FROM public_event
                """);
        jdbc.update("""
                INSERT INTO public_event_instrument (public_event_id, instrument_id, match_reason, confidence)
                SELECT pe.id, 'SYN-SGX-001', 'twin', 0.9 FROM public_event pe WHERE pe.classifier_version = 'twin-v1'
                """);

        store("two.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();

        assertThat(count("public_event")).isEqualTo(3);
    }

    @Test
    void noReportIsWrittenForAnEventWithoutAnAlertOfSixtyOrMore() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.ABSTAINED, null, "MODEL_ABSTAINED", 1));
        pipeline.processPending();
        jdbc.update("UPDATE alert SET score = 59.99");
        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.GENERATED, report("x"), null, 1));
        reportRequests.clear();

        assertThat(reports.refresh(20)).isZero();

        assertThat(reportRequests).isEmpty();
        assertThat(count("event_report")).isZero();
    }

    @Test
    void failedAttemptsAreCountedAndStopAfterThreeButAnUnavailableModelIsNotAFailure() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.PENDING, null, "MODEL_DISABLED", 0));
        pipeline.processPending();
        reports.refresh(20);
        assertThat(count("event_report_attempt")).as("an unavailable model is not a failure").isZero();

        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.ABSTAINED, null, "UNSUPPORTED_FIGURE", 2));
        reportRequests.clear();
        for (int pass = 0; pass < 5; pass++) reports.refresh(20);

        assertThat(reportRequests).as("tried three times, then left alone").hasSize(3);
        assertThat(jdbc.queryForObject("SELECT attempts FROM event_report_attempt", Integer.class)).isEqualTo(3);
        assertThat(count("event_report")).isZero();
    }

    @Test
    void aSuccessfulReportClearsTheAttemptCountAndIsAudited() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.ABSTAINED, null, "UNSUPPORTED_FIGURE", 2));
        pipeline.processPending();
        assertThat(count("event_report_attempt")).isOne();

        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.GENERATED, report("ok"), null, 1));
        reports.refresh(20);

        assertThat(count("event_report")).isOne();
        assertThat(count("event_report_attempt")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_outbox WHERE event_type = 'alert.report-generated'", Long.class)).isOne();
    }

    @Test
    void theModelIsOnlyToldPublicThings() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();

        ReportInput input = reportRequests.getFirst();

        assertThat(input.instruments()).extracting(ReportInput.InstrumentRef::instrumentId).containsExactly("SYN-SGX-001");
        assertThat(input.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.title()).isEqualTo("SYN1 posts synthetic earnings");
            assertThat(evidence.sourceDomain()).isEqualTo("one.example");
        });
    }

    @Test
    void theMigrationTurnsAnExistingBriefingIntoVersionOneOfItsEventsReport() throws Exception {
        SourceResult source = store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();
        jdbc.update("DELETE FROM event_report"); // the state before the migration: briefings only
        String migration = new String(getClass().getResourceAsStream("/db/migration/V12__event_reports.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        int from = migration.indexOf("INSERT INTO event_report");
        String backfill = migration.substring(from, migration.indexOf(";", from) + 1);

        jdbc.execute(backfill);

        assertThat(count("event_report")).as("one report per event, not per alert").isOne();
        assertThat(jdbc.queryForObject("SELECT schema_version FROM event_report", String.class)).isEqualTo("briefing-v1-legacy");
        assertThat(jdbc.queryForObject("SELECT report->>'headline' FROM event_report", String.class)).isEqualTo(source.getTitle());
        assertThat(jdbc.queryForObject("SELECT report->'executiveSummary'->0->>'text' FROM event_report", String.class))
                .isEqualTo("The issuer reported synthetic earnings.");
        assertThat(jdbc.queryForObject("SELECT source_ids->>0 FROM event_report", String.class)).isEqualTo(source.getId().toString());
        assertThat(count("briefing")).as("the old table is untouched").isEqualTo(2);
    }

    @Test
    void theCardSaysWhatTheReportSaysAndTheDetailCarriesTheReportItsSourcesAndTheFixedText() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();
        store("two.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();

        List<AgentEventModels.EventCard> cards = eventQueries.list("syn-agent-001", false, 25).items();

        assertThat(cards).as("one card for one event").hasSize(1);
        AgentEventModels.EventCard card = cards.getFirst();
        assertThat(card.headline()).isEqualTo("v2");
        assertThat(card.summary()).isEqualTo("One. Two.");
        assertThat(card.uncertainty()).isEqualTo("Uncertain.");
        assertThat(card.aiGenerated()).isTrue();
        AgentEventModels.ReportView report = eventQueries.detail(card.eventId(), "syn-agent-001").report();
        assertThat(report.version()).isEqualTo(2);
        assertThat(report.versionCount()).isEqualTo(2);
        assertThat(report.schemaVersion()).isEqualTo("alert-client-report-v2");
        assertThat(report.report().get("headline").asText()).isEqualTo("v2");
        assertThat(report.sources()).extracting(AgentEventModels.ReportSource::domain).containsExactly("one.example", "two.example");
        assertThat(report.sources()).allMatch(AgentEventModels.ReportSource::usedInReport);
        assertThat(report.disclaimer()).isEqualTo(ReportText.DISCLAIMER);
        assertThat(report.portfolioLines().get("SYN-SGX-001"))
                .startsWith("Your portfolio has exposure to ").endsWith(", which may be affected by this event.");
        assertThat(report.report().toString()).as("nothing about a client in what the model wrote")
                .doesNotContain("syn-client").doesNotContain("syn-account");
        assertThatThrownBy(() -> eventQueries.detail(card.eventId(), "someone-else")).isInstanceOf(AlertNotFoundException.class);
    }

    @Test
    void beforeAReportExistsTheCardFallsBackToTheFirstBriefingAndTheDetailHasNoReport() {
        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.PENDING, null, "MODEL_DISABLED", 0));
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();

        AgentEventModels.EventCard card = eventQueries.list("syn-agent-001", false, 25).items().getFirst();

        assertThat(card.headline()).isEqualTo("SYN1 posts synthetic earnings");
        assertThat(card.summary()).isEqualTo("The issuer reported synthetic earnings.");
        assertThat(eventQueries.detail(card.eventId(), "syn-agent-001").report()).isNull();
    }

    @Test
    void existingEventsAreLinkedToTheirFirstSourceByTheMigration() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public_event pe
                 WHERE NOT EXISTS (SELECT 1 FROM public_event_source s
                                    WHERE s.public_event_id = pe.id AND s.source_result_id = pe.source_result_id)
                """, Long.class)).isZero();
    }
}
