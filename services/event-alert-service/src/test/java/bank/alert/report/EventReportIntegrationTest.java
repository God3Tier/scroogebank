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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
    @Autowired jakarta.persistence.EntityManager entityManager;

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final AtomicReference<EventDirection> direction = new AtomicReference<>(EventDirection.POSITIVE);
    /** The direction the classifier gives to each source in turn; when empty it uses {@code direction}. */
    private final Deque<EventDirection> directions = new ArrayDeque<>();
    private final AtomicReference<List<String>> affectedTickers = new AtomicReference<>(List.of("SYN-SGX-001"));
    /** The tickers the classifier names for a source from this domain; other sources use {@code affectedTickers}. */
    private final java.util.Map<String, List<String>> tickersByDomain = new java.util.HashMap<>();
    private final AtomicReference<ReportOutcome> reportOutcome = new AtomicReference<>();
    private final List<ReportInput> reportRequests = new ArrayList<>();
    private SourcePipeline pipeline;
    private EventReportService reports;
    private AgentEventQueryService eventQueries;

    @BeforeEach
    void seed() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
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
                        new EventClassification("Synthetic earnings event", EventType.EARNINGS,
                                directions.isEmpty() ? direction.get() : directions.poll(),
                                new BigDecimal("0.90"),
                                tickersByDomain.getOrDefault(evidence.sourceDomain(), affectedTickers.get()).stream()
                                        .map(id -> new AffectedInstrument(id, "ref")).toList(),
                                List.of("ref"), "fixture-model-v1", "prompt-v1", clock.instant()), null, 1),
                input -> new BriefingOutcome(BriefingStatus.GENERATED,
                        new AgentBriefing("Synthetic issuer update", "The issuer reported synthetic earnings.",
                                "Synthetic.", input.evidence().stream().map(e -> e.referenceId()).toList(),
                                List.of("Review."), true, "fixture-model-v1", "prompt-v1", clock.instant()), null, 1),
                generation, provider, new EventSeverityPolicy());
        ClientReportGenerator generator = input -> {
            reportRequests.add(input);
            ReportOutcome outcome = reportOutcome.get();
            return outcome.status() == ReportOutcome.Status.GENERATED
                    ? new ReportOutcome(ReportOutcome.Status.GENERATED, report("v" + reportRequests.size()), null, 1,
                            "fixture-model-v1", "alert-client-report-v2.1", clock.instant())
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
    void everythingFoundForATickerInOnePassIsOneEventAndOneReportWhateverItsDirection() {
        directions.addAll(List.of(EventDirection.POSITIVE, EventDirection.NEGATIVE, EventDirection.UNCERTAIN));
        SourceResult first = store("one.example", "2026-09-26T20:00:00Z", "a");
        SourceResult second = store("two.example", "2026-09-26T21:00:00Z", "b");
        SourceResult third = store("three.example", "2026-09-26T22:00:00Z", "c");

        pipeline.processPending();

        assertThat(count("public_event")).as("one event for the ticker").isOne();
        assertThat(count("public_event_source")).isEqualTo(3);
        assertThat(count("event_report")).isOne();
        assertThat(reportRequests).hasSize(1);
        assertThat(reportRequests.getFirst().evidence()).hasSize(3);
        assertThat(reportSources(1)).containsExactlyInAnyOrder(
                first.getId().toString(), second.getId().toString(), third.getId().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert", Long.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) = count(DISTINCT client_ref) FROM alert", Boolean.class))
                .as("one alert per client, not one per article").isTrue();
        assertThat(jdbc.queryForObject("SELECT instrument_id FROM public_event", String.class)).isEqualTo("SYN-SGX-001");
    }

    @Test
    void theNextRunsNewArticlesStartANewEventAndTheReportDoesNotRepeatTheFirst() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();
        assertThat(count("event_report")).isOne();

        store("two.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();

        assertThat(count("public_event")).as("the first event was closed by its report").isEqualTo(2);
        assertThat(count("event_report")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT count(*) FROM public_event_source GROUP BY public_event_id", Long.class))
                .containsExactly(1L, 1L);
        assertThat(reportRequests.get(0).alreadyReported()).isEmpty();
        assertThat(reportRequests.get(1).alreadyReported()).singleElement().satisfies(prior -> {
            assertThat(prior.headline()).isEqualTo("v1");
            assertThat(prior.summary()).containsExactly("One.", "Two.");
        });
    }

    @Test
    void theFirstReportIsWrittenOncePerEventEvenIfThePipelineRunsAgain() {
        store("one.example", "2026-09-26T22:00:00Z", "a");

        pipeline.processPending();
        pipeline.processPending();

        assertThat(reportRequests).hasSize(1);
        assertThat(count("event_report")).isOne();
    }

    @Test
    void anEventWithNoReportYetKeepsTakingNewArticlesAndTheReportCoversThemAll() {
        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.ABSTAINED, null, "MODEL_ABSTAINED", 1));
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();
        assertThat(count("public_event")).isOne();
        assertThat(count("event_report")).isZero();

        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.GENERATED, report("x"), null, 1));
        store("two.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();

        assertThat(count("public_event")).as("the second article joined the open event").isOne();
        assertThat(count("public_event_source")).isEqualTo(2);
        assertThat(count("event_report")).isOne();
        assertThat(reportRequests.getLast().evidence()).hasSize(2);
    }

    @Test
    void anOpenEventOlderThanSeventyTwoHoursTakesNoMoreArticles() {
        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.ABSTAINED, null, "MODEL_ABSTAINED", 1));
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();
        jdbc.update("UPDATE public_event SET created_at = created_at - interval '73 hours'");
        reportOutcome.set(ReportOutcome.of(ReportOutcome.Status.GENERATED, report("x"), null, 1));

        store("two.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();

        assertThat(count("public_event")).isEqualTo(2);
    }

    @Test
    void anArticleNamingTwoTickersFeedsBothTickersEvents() {
        now.set(Instant.parse("2026-10-30T00:00:00Z")); // the holdings of the real tickers are valued late in October
        affectedTickers.set(List.of("NASDAQ-AAPL", "SGX-D05"));
        store("one.example", "2026-10-29T22:00:00Z", "a");

        pipeline.processPending();

        assertThat(jdbc.queryForList("SELECT instrument_id FROM public_event ORDER BY 1", String.class))
                .containsExactly("NASDAQ-AAPL", "SGX-D05");
        assertThat(count("public_event_source")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT source_result_id) FROM public_event_source", Long.class)).isOne();
    }

    @Test
    void severalTickersEachGetOneEventNotOnePerArticle() {
        now.set(Instant.parse("2026-10-30T00:00:00Z"));
        tickersByDomain.put("one.example", List.of("NASDAQ-AAPL"));
        tickersByDomain.put("two.example", List.of("NASDAQ-AAPL"));
        tickersByDomain.put("three.example", List.of("SGX-D05"));
        tickersByDomain.put("four.example", List.of("SGX-D05"));
        store("one.example", "2026-10-29T20:00:00Z", "a");
        store("two.example", "2026-10-29T21:00:00Z", "b");
        store("three.example", "2026-10-29T22:00:00Z", "c");
        store("four.example", "2026-10-29T23:00:00Z", "d");

        pipeline.processPending();

        assertThat(count("public_event")).as("two tickers, two events, four articles").isEqualTo(2);
        assertThat(count("public_event_source")).isEqualTo(4);
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
        store("two.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();

        List<AgentEventModels.EventCard> cards = eventQueries.list("syn-agent-001", false, 25).items();

        assertThat(cards).as("one card for the ticker").hasSize(1);
        AgentEventModels.EventCard card = cards.getFirst();
        assertThat(card.instrumentId()).isEqualTo("SYN-SGX-001");
        assertThat(card.ticker()).isEqualTo("SYN1");
        assertThat(card.issuer()).isEqualTo("Synthetic Issuer One");
        assertThat(card.sourceCount()).isEqualTo(2);
        assertThat(card.reportVersion()).isEqualTo(1);
        assertThat(card.headline()).isEqualTo("v1");
        assertThat(card.summary()).isEqualTo("One. Two.");
        assertThat(card.uncertainty()).isEqualTo("Uncertain.");
        assertThat(card.aiGenerated()).isTrue();
        AgentEventModels.ReportView report = eventQueries.detail(card.eventId(), "syn-agent-001").report();
        assertThat(report.version()).isEqualTo(1);
        assertThat(report.versionCount()).isEqualTo(1);
        assertThat(report.schemaVersion()).isEqualTo("alert-client-report-v2");
        assertThat(report.report().get("headline").asText()).isEqualTo("v1");
        assertThat(report.sources()).extracting(AgentEventModels.ReportSource::domain).containsExactly("one.example", "two.example");
        assertThat(report.sources()).allMatch(AgentEventModels.ReportSource::usedInReport);
        assertThat(report.sources()).allMatch(source -> source.id() != null && source.retrievedAt() != null);
        assertThat(report.disclaimer()).isEqualTo(ReportText.DISCLAIMER);
        assertThat(report.portfolioLines().get("SYN-SGX-001"))
                .startsWith("Your portfolio has exposure to ").endsWith(", which may be affected by this event.");
        assertThat(report.report().toString()).as("nothing about a client in what the model wrote")
                .doesNotContain("syn-client").doesNotContain("syn-account");
        assertThatThrownBy(() -> eventQueries.detail(card.eventId(), "someone-else")).isInstanceOf(AlertNotFoundException.class);
    }

    @Test
    void anOlderEventWithNoTickerColumnStillListsWithItsTickerFromItsInstrumentRow() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();
        jdbc.update("UPDATE public_event SET instrument_id = NULL"); // the state of an event written before V16

        AgentEventModels.EventCard card = eventQueries.list("syn-agent-001", false, 25).items().getFirst();

        assertThat(card.instrumentId()).isEqualTo("SYN-SGX-001");
        assertThat(card.ticker()).isEqualTo("SYN1");
        assertThat(card.sourceCount()).isOne();
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

    private static final String CRM_CLIENT = "11111111-2222-4333-8444-555555555555";

    private ReportReviewService reviews() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        @SuppressWarnings("unchecked")
        ObjectProvider<CrmDirectory> noDirectory = mock(ObjectProvider.class);
        when(noDirectory.getIfAvailable()).thenReturn(null);
        return new ReportReviewService(jdbc, eventQueries, alerts, new bank.alert.service.AlertStateMachine(),
                new CurrentOwnership(jdbc, noDirectory, clock, outbox, mapper, 1000), outbox, mapper,
                new bank.alert.config.ReportReviewProperties(null, null, false), clock, 1000);
    }

    /** One event with a report whose first alert now belongs to an INFORMED CRM-style client, as in production. */
    private UUID[] eventWithInformedCrmClient() {
        store("one.example", "2026-09-26T22:00:00Z", "a");
        pipeline.processPending();
        jdbc.update("UPDATE alert SET client_ref = ?, status = 'INFORMED' WHERE id = (SELECT id FROM alert ORDER BY id LIMIT 1)", CRM_CLIENT);
        entityManager.flush();
        entityManager.clear();
        UUID eventId = jdbc.queryForObject("SELECT id FROM public_event", UUID.class);
        UUID reportId = jdbc.queryForObject("SELECT id FROM event_report", UUID.class);
        UUID alertId = jdbc.queryForObject("SELECT id FROM alert WHERE client_ref = ?", UUID.class, CRM_CLIENT);
        return new UUID[] {eventId, reportId, alertId};
    }

    private String agentOf(UUID alertId) {
        return jdbc.queryForObject("SELECT assigned_agent_subject FROM alert WHERE id = ?", String.class, alertId);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode sections(String firstSentence) {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode sections = mapper.createObjectNode();
        sections.putArray("executiveSummary").add(firstSentence).add("Two.");
        sections.putArray("keyFacts");
        sections.putArray("whyItMatters").add("Why.");
        sections.putArray("whatToMonitor").add("Watch one").add("Watch two");
        sections.put("uncertainty", "Uncertain.");
        return sections;
    }

    @Test
    void anEditIsCheckedSavedAndUsedByTheApproval() {
        UUID[] ids = eventWithInformedCrmClient();
        String agent = agentOf(ids[2]);
        ReportReviewService reviews = reviews();

        assertThatThrownBy(() -> reviews.saveEdit(ids[0], agent, 1, sections("Revenue rose 42 per cent.")))
                .isInstanceOf(ReportEdits.EditRefused.class)
                .satisfies(e -> {
                    ReportEdits.Violation v = ((ReportEdits.EditRefused) e).violation();
                    assertThat(v.code()).isEqualTo("UNSUPPORTED_FIGURE");
                    assertThat(v.section()).isEqualTo("executiveSummary");
                    assertThat(v.index()).isZero();
                });
        assertThatThrownBy(() -> reviews.saveEdit(ids[0], agent, 1, sections("We recommend buying more.")))
                .isInstanceOf(ReportEdits.EditRefused.class)
                .satisfies(e -> assertThat(((ReportEdits.EditRefused) e).violation().code()).isEqualTo("ADVICE_LANGUAGE"));
        assertThat(count("report_edit")).as("a refused edit saves nothing").isZero();

        var review = reviews.saveEdit(ids[0], agent, 1, sections("One, said more plainly."));
        assertThat(review.editedSections().path("executiveSummary").get(0).asText()).isEqualTo("One, said more plainly.");
        assertThat(review.approval()).isNull();

        var approval = reviews.approve(ids[0], agent, 1);
        assertThat(approval.edited()).isTrue();
        assertThat(approval.valid()).isTrue();
        assertThat(approval.validUntil()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(count("report_edit")).as("the edit becomes the approval").isZero();
        assertThat(reviews.review(ids[0], agent).approvedSections().path("executiveSummary").get(0).asText())
                .isEqualTo("One, said more plainly.");
        assertThat(jdbc.queryForObject("SELECT final_text->'executiveSummary'->0->>'text' FROM approved_report", String.class))
                .isEqualTo("One, said more plainly.");
        assertThat(jdbc.queryForObject("SELECT report->'executiveSummary'->0->>'text' FROM event_report", String.class))
                .as("other agents still see the original").isEqualTo("One.");

        assertThatThrownBy(() -> reviews.approve(ids[0], agent, 1))
                .as("approval is final").isInstanceOf(bank.alert.service.AlertMutationConflictException.class);
        assertThatThrownBy(() -> reviews.saveEdit(ids[0], agent, 1, sections("Changed again.")))
                .as("an approved report cannot be edited").isInstanceOf(bank.alert.service.AlertMutationConflictException.class);
        assertThat(outbox.findAll()).extracting(o -> o.getEventType())
                .contains("alert.report-edited", "alert.report-approved");
    }

    @Test
    void approvingNeedsAClientInPlanClientBriefingAndTheCurrentVersion() {
        UUID[] ids = eventWithInformedCrmClient();
        String agent = agentOf(ids[2]);
        assertThatThrownBy(() -> reviews().approve(ids[0], agent, 2))
                .isInstanceOf(bank.alert.service.AlertMutationConflictException.class);
        jdbc.update("UPDATE alert SET status = 'NEW'");
        entityManager.flush();
        entityManager.clear();
        assertThatThrownBy(() -> reviews().approve(ids[0], agent, 1))
                .isInstanceOf(bank.alert.service.AlertMutationConflictException.class)
                .hasMessageContaining("Plan client briefing");
        assertThatThrownBy(() -> reviews().approve(ids[0], "someone-else", 1)).isInstanceOf(AlertNotFoundException.class);
    }

    @Test
    void thePackageReleasesOnlyInformedCrmClientsWithAValidApproval() {
        UUID[] ids = eventWithInformedCrmClient();
        String agent = agentOf(ids[2]);
        ReportReviewService reviews = reviews();
        UUID synthetic = jdbc.queryForObject("SELECT id FROM alert WHERE id <> ? LIMIT 1", UUID.class, ids[2]);

        assertThat(reviews.packageFor(agent, ids[0], List.of(ids[2])).getFirst().outcome()).isEqualTo("NOT_APPROVED");
        var approval = reviews.approve(ids[0], agent, 1);

        var items = reviews.packageFor(agent, ids[0], List.of(ids[2], UUID.randomUUID(), synthetic));
        assertThat(items.get(0)).satisfies(item -> {
            assertThat(item.outcome()).isEqualTo("OK");
            assertThat(item.approvalId()).isEqualTo(approval.approvalId());
            assertThat(item.clientRef()).isEqualTo(CRM_CLIENT);
            assertThat(item.summaryLine()).as("no AI sentence, so the first sentence").isEqualTo("One.");
            assertThat(item.finalHash()).hasSize(64);
        });
        assertThat(items.get(1).outcome()).isEqualTo("NOT_AUTHORISED");
        assertThat(items.get(2).outcome()).as("a synthetic client never receives mail").isEqualTo("NOT_AUTHORISED");

        jdbc.update("UPDATE event_report SET summary_sentence = 'Synthetic Co reported synthetic earnings.'");

        entityManager.flush();

        entityManager.clear();
        assertThat(reviews.packageFor(agent, ids[0], List.of(ids[2])).getFirst().summaryLine())
                .isEqualTo("Synthetic Co reported synthetic earnings.");
        assertThat(reviews.approved(agent, CRM_CLIENT)).singleElement()
                .satisfies(item -> assertThat(item.approvalId()).isEqualTo(approval.approvalId()));

        jdbc.update("UPDATE alert SET status = 'DISMISSED' WHERE id = ?", ids[2]);

        entityManager.flush();

        entityManager.clear();
        assertThat(reviews.packageFor(agent, ids[0], List.of(ids[2])).getFirst().outcome()).isEqualTo("NOT_INFORMED");
        jdbc.update("UPDATE alert SET status = 'INFORMED' WHERE id = ?", ids[2]);
        entityManager.flush();
        entityManager.clear();
        assertThat(reviews.packageFor(agent, ids[0], List.of(ids[2])).getFirst().outcome())
                .as("Dismiss and back again reuses the valid approval").isEqualTo("OK");

        now.set(NOW.plus(Duration.ofDays(8)));
        assertThat(reviews.packageFor(agent, ids[0], List.of(ids[2])).getFirst().outcome()).isEqualTo("NOT_APPROVED");
        assertThat(reviews.sendCheck(agent, List.of(ids[2]))).singleElement()
                .satisfies(check -> assertThat(check.outcome()).isEqualTo("EXPIRED"));
    }

    @Test
    void sentOutIsFinalIdempotentAndClearsTheApprovedText() {
        UUID[] ids = eventWithInformedCrmClient();
        String agent = agentOf(ids[2]);
        ReportReviewService reviews = reviews();
        reviews.approve(ids[0], agent, 1);
        jdbc.update("UPDATE alert SET status = 'SENT_OUT' WHERE id <> ? AND assigned_agent_subject = ?", ids[2], agent);
        entityManager.flush();
        entityManager.clear();
        assertThat(reviews.sendCheck(agent, List.of(ids[2]))).singleElement()
                .satisfies(check -> assertThat(check.outcome()).isEqualTo("OK"));
        UUID draftId = UUID.randomUUID();

        assertThat(reviews.sentOut(agent, List.of(ids[2]), draftId, "ses-1")).singleElement()
                .satisfies(item -> assertThat(item.outcome()).isEqualTo("SENT_OUT"));
        assertThat(reviews.sentOut(agent, List.of(ids[2]), draftId, "ses-1")).singleElement()
                .satisfies(item -> assertThat(item.outcome()).isEqualTo("ALREADY_SENT_OUT"));
        assertThat(jdbc.queryForObject("SELECT status FROM alert WHERE id = ?", String.class, ids[2])).isEqualTo("SENT_OUT");
        assertThat(reviews.sentOutList(agent, false, 10)).singleElement().satisfies(row -> {
            assertThat(row.alertId()).isEqualTo(ids[2]);
            assertThat(row.headline()).isNotBlank();
        });
        assertThat(reviews.sentOutList("admin", true, 10)).hasSize(1);
        assertThat(count("approved_report WHERE final_text IS NULL"))
                .as("every client on it is Sent Out, so its text is cleared").isOne();
        assertThat(reviews.sendCheck(agent, List.of(ids[2])).getFirst().outcome()).isEqualTo("NOT_INFORMED");
    }

    @Test
    void theCombinedPdfIsTheSameForTheSameApprovalsAndListsWhereEachCompanyStarts() {
        UUID[] ids = eventWithInformedCrmClient();
        String agent = agentOf(ids[2]);
        ReportReviewService reviews = reviews();
        var approval = reviews.approve(ids[0], agent, 1);

        var first = reviews.combinedPdf(agent, List.of(approval.approvalId()), "Alex Tan");
        now.set(NOW.plus(Duration.ofDays(2)));
        var second = reviews.combinedPdf(agent, List.of(approval.approvalId()), "Alex Tan");

        assertThat(second.pdfBase64()).as("byte for byte the same on another day").isEqualTo(first.pdfBase64());
        assertThat(first.documentDate()).hasToString("2026-09-27");
        assertThat(first.fileName()).isEqualTo("Portfolio-update-2026-09-27.pdf");
        assertThat(first.sections()).singleElement().satisfies(section -> {
            assertThat(section.startPage()).as("cover, contents, then the company").isEqualTo(3);
            assertThat(section.finalHash()).hasSize(64);
        });
        byte[] pdf = java.util.Base64.getDecoder().decode(first.pdfBase64());
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(CRM_CLIENT);

        assertThatThrownBy(() -> reviews.combinedPdf("someone-else", List.of(approval.approvalId()), null))
                .isInstanceOf(AlertNotFoundException.class);
        assertThat(reviews.singlePdf(ids[0], null, null, true).pdf()).as("admins can read a report").isNotEmpty();
    }

    @Test
    void aLaterArticleStartsTheNextEventAndLeavesTheEarlierOneAlone() {
        eventWithInformedCrmClient();
        store("two.example", "2026-09-26T23:00:00Z", "b");
        pipeline.processPending();
        assertThat(count("public_event")).as("the later article starts the next event").isEqualTo(2);
    }
}
