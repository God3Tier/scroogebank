package bank.alert.portfolio;

import bank.alert.ai.AffectedInstrument;
import bank.alert.ai.AgentBriefing;
import bank.alert.ai.EventClassification;
import bank.alert.ai.EventDirection;
import bank.alert.ai.EventType;
import bank.alert.crm.AccountContext;
import bank.alert.crm.CrmDirectory;
import bank.alert.crm.CurrentOwnership;
import bank.alert.crm.DirectoryUnavailableException;
import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertStatus;
import bank.alert.domain.SourceResult;
import bank.alert.event.AgentEventActionService;
import bank.alert.event.AgentEventModels.ActionMember;
import bank.alert.event.AgentEventModels.ActionRequest;
import bank.alert.event.AgentEventModels.ActionResult;
import bank.alert.event.AgentEventModels.EventDetail;
import bank.alert.event.AgentEventModels.MemberOutcome;
import bank.alert.event.AgentEventQueryService;
import bank.alert.holdings.CurrentHoldingsReader;
import bank.alert.holdings.DatabaseHoldingsProvider;
import bank.alert.holdings.HoldingsReadRequest;
import bank.alert.holdings.ValuationFreshnessPolicy;
import bank.alert.operations.OperationalMetrics;
import bank.alert.processing.AlertGenerationOutcome;
import bank.alert.processing.AlertGenerationRequest;
import bank.alert.processing.AlertGenerationService;
import bank.alert.repository.AlertListRepository;
import bank.alert.repository.AlertRepository;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.repository.HoldingSnapshotRepository;
import bank.alert.repository.SourceResultRepository;
import bank.alert.scoring.ExposureCalculator;
import bank.alert.scoring.ExposureResult;
import bank.alert.scoring.MaterialityScorer;
import bank.alert.service.AlertStateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {"alert.holdings.mode=database", "alert.audit.max-pending=100"})
@Import({PortfolioService.class, CurrentHoldingsReader.class, DatabaseHoldingsProvider.class,
        CurrentOwnership.class, AgentEventQueryService.class, AgentEventActionService.class,
        AlertStateMachine.class, ValuationFreshnessPolicy.class, AlertListRepository.class,
        CurrentHoldingsIntegrationTest.Config.class})
class CurrentHoldingsIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-30T02:00:00Z");
    private static final Instant VALUED = NOW.minusSeconds(3600);
    private static final UUID CLIENT_1 = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final UUID CLIENT_2 = UUID.fromString("c0000000-0000-4000-8000-000000000002");
    private static final UUID ACCOUNT_1 = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_2 = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID ACCOUNT_SAVINGS = UUID.fromString("a0000000-0000-4000-8000-000000000003");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** A CRM whose records and failures the test controls. */
    static class StubDirectory implements CrmDirectory {
        final Map<UUID, AccountContext> accounts = new HashMap<>();
        /** The agent who owns each client now; accounts are served with their client's owner. */
        final Map<UUID, String> owners = new HashMap<>();
        boolean down;
        /** Tokens the CRM's own scoped check refuses, whatever the directory says about ownership. */
        final java.util.Set<String> refusedTokens = new java.util.HashSet<>();

        @Override
        public boolean agentCanAccess(UUID accountId, String bearerToken) {
            if (down) throw new DirectoryUnavailableException("down");
            return !refusedTokens.contains(bearerToken);
        }

        @Override
        public Map<UUID, AccountContext> accounts(Collection<UUID> ids) {
            if (down) throw new DirectoryUnavailableException("down");
            Map<UUID, AccountContext> result = new HashMap<>();
            ids.forEach(id -> {
                AccountContext account = accounts.get(id);
                if (account != null) {
                    result.put(id, new AccountContext(account.accountId(), account.clientId(), account.type(),
                            account.status(), account.currency(), owners.get(account.clientId())));
                }
            });
            return result;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean StubDirectory stubDirectory() { return new StubDirectory(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean OperationalMetrics operationalMetrics(MeterRegistry registry) { return new OperationalMetrics(registry); }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @Autowired PortfolioService portfolios;
    @Autowired DatabaseHoldingsProvider provider;
    @Autowired CurrentOwnership ownership;
    @Autowired AgentEventQueryService eventQueries;
    @Autowired AgentEventActionService eventActions;
    @Autowired AlertListRepository alertList;
    @Autowired AlertRepository alerts;
    @Autowired StubDirectory crm;
    @Autowired JdbcTemplate jdbc;
    @Autowired SourceResultRepository sourceResults;
    @Autowired HoldingSnapshotRepository holdingSnapshots;
    @Autowired AuditOutboxRepository outbox;
    @Autowired ObjectMapper mapper;
    @Autowired Clock clock;
    @Autowired MeterRegistry meters;
    @Autowired CurrentHoldingsReader reader;

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO instrument (id, ticker, issuer_name, sector, currency)
                VALUES ('SYN-SGX-001', 'SYN1', 'Synthetic Issuer One', 'Synthetic', 'SGD'),
                       ('SYN-SGX-002', 'SYN2', 'Synthetic Issuer Two', 'Synthetic', 'SGD')
                ON CONFLICT (id) DO NOTHING
                """);
        crm.down = false;
        crm.accounts.clear();
        crm.owners.clear();
        crm.accounts.put(ACCOUNT_1, new AccountContext(ACCOUNT_1, CLIENT_1, "INVESTMENT", "ACTIVE", "SGD", null));
        crm.accounts.put(ACCOUNT_2, new AccountContext(ACCOUNT_2, CLIENT_2, "INVESTMENT", "ACTIVE", "SGD", null));
        crm.accounts.put(ACCOUNT_SAVINGS, new AccountContext(ACCOUNT_SAVINGS, CLIENT_1, "SAVINGS", "ACTIVE", "SGD", null));
        crm.owners.put(CLIENT_1, "agent-a");
        crm.owners.put(CLIENT_2, "agent-a");
    }

    private static PortfolioReplaceRequest.Position position(String instrument, String qty, String price) {
        return new PortfolioReplaceRequest.Position(instrument, new BigDecimal(qty), new BigDecimal(price),
                "SGD", BigDecimal.ONE, VALUED);
    }

    private PortfolioResponse put(UUID account, String key, long expected, PortfolioReplaceRequest.Position... p) {
        return portfolios.replace(account, "agent-a", "user-token", key, UUID.randomUUID(),
                new PortfolioReplaceRequest(expected, "manual-entry", List.of(p)));
    }

    @Test
    void recordsReplacesAndClearsAPortfolioWithRevisionsAndIdempotency() {
        assertThatThrownBy(() -> portfolios.get(ACCOUNT_1, "agent-a", false))
                .isInstanceOfSatisfying(PortfolioApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

        PortfolioResponse first = put(ACCOUNT_1, "key-00000001", 0, position("SYN-SGX-001", "10", "1.0"));
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.totalSgd()).isEqualByComparingTo("10.0000");

        PortfolioResponse replay = put(ACCOUNT_1, "key-00000001", 0, position("SYN-SGX-001", "10", "1.0"));
        assertThat(replay.revision()).isEqualTo(1);
        assertThatThrownBy(() -> put(ACCOUNT_1, "key-00000001", 0, position("SYN-SGX-001", "11", "1.0")))
                .isInstanceOfSatisfying(PortfolioApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> put(ACCOUNT_1, "key-00000002", 0, position("SYN-SGX-001", "12", "1.0")))
                .isInstanceOfSatisfying(PortfolioApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        PortfolioResponse second = put(ACCOUNT_1, "key-00000003", 1,
                position("SYN-SGX-001", "10", "1.0"), position("SYN-SGX-002", "90", "1.0"));
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.totalSgd()).isEqualByComparingTo("100.0000");

        PortfolioResponse cleared = put(ACCOUNT_1, "key-00000004", 2);
        assertThat(cleared.revision()).isEqualTo(3);
        assertThat(cleared.positions()).isEmpty(); // recorded empty, not "never recorded"
        assertThat(portfolios.get(ACCOUNT_1, "agent-a", false).revision()).isEqualTo(3);
        assertThat(outbox.findAll()).extracting(e -> e.getEventType())
                .contains("alert.portfolio-replaced", "alert.portfolio-cleared");
        assertThat(outbox.findAll()).noneMatch(e -> e.getPayload().contains("quantity"));
    }

    @Test
    void aWriteNeedsTheCrmToAgreeWithTheAgentsOwnToken() {
        put(ACCOUNT_1, "key-15000001", 0, position("SYN-SGX-001", "10", "1.0"));
        crm.refusedTokens.add("user-token");

        // the directory still says agent-a owns the account, but the CRM refuses this token
        assertThat(statusOf(() -> portfolios.replace(ACCOUNT_1, "agent-a", "user-token", "key-15000002",
                UUID.randomUUID(), new PortfolioReplaceRequest(1, "entry", List.of(position("SYN-SGX-001", "20", "1.0"))))))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT revision FROM current_portfolio WHERE account_id = ?", Long.class,
                ACCOUNT_1)).isOne();

        crm.refusedTokens.clear();
        crm.down = true; // an unreachable CRM is unavailable, not "allowed"
        assertThatThrownBy(() -> portfolios.replace(ACCOUNT_1, "agent-a", "user-token", "key-15000003",
                UUID.randomUUID(), new PortfolioReplaceRequest(1, "entry", List.of(position("SYN-SGX-001", "20", "1.0")))))
                .isInstanceOf(DirectoryUnavailableException.class);
    }

    @Test
    void refusesInvalidAndUnauthorisedWrites() {
        assertThat(statusOf(() -> put(ACCOUNT_1, "key-10000001", 0, position("NOPE", "1", "1"))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf(() -> put(ACCOUNT_1, "key-10000002", 0,
                position("SYN-SGX-001", "1", "1"), position("SYN-SGX-001", "2", "1"))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf(() -> put(ACCOUNT_1, "key-10000003", 0, position("SYN-SGX-001", "-1", "1"))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf(() -> portfolios.replace(ACCOUNT_1, "agent-a", "user-token", "key-10000004", UUID.randomUUID(),
                new PortfolioReplaceRequest(0, "manual-entry", List.of(new PortfolioReplaceRequest.Position(
                        "SYN-SGX-001", BigDecimal.ONE, BigDecimal.ONE, "SGD", BigDecimal.ONE, NOW.plusSeconds(60)))))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf(() -> put(ACCOUNT_SAVINGS, "key-10000005", 0, position("SYN-SGX-001", "1", "1"))))
                .isEqualTo(HttpStatus.CONFLICT);
        // another agent cannot see or write the account: indistinguishable from a missing one
        assertThat(statusOf(() -> portfolios.replace(ACCOUNT_1, "agent-b", "user-token", "key-10000006", UUID.randomUUID(),
                new PortfolioReplaceRequest(0, "x", List.of()))))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> put(UUID.randomUUID(), "key-10000007", 0))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM current_portfolio", Long.class)).isZero();

        crm.down = true;
        assertThatThrownBy(() -> put(ACCOUNT_1, "key-10000008", 0)).isInstanceOf(DirectoryUnavailableException.class);
    }

    @Test
    void providerReturnsWholeAccountsSoOneAffectedHoldingIsNotOneHundredPercent() {
        put(ACCOUNT_1, "key-20000001", 0, position("SYN-SGX-001", "10", "1.0"), position("SYN-SGX-002", "90", "1.0"));
        put(ACCOUNT_2, "key-20000002", 0, position("SYN-SGX-002", "500", "1.0"));

        var result = provider.read(new HoldingsReadRequest(java.util.Set.of("SYN-SGX-001"), NOW, "agent-a"));

        assertThat(result.positions()).hasSize(2); // account 1 only, both securities; account 2 holds no match
        ExposureResult exposure = new ExposureCalculator().calculate(result.positions(), java.util.Set.of("SYN-SGX-001"));
        assertThat(exposure.evaluable()).isTrue();
        assertThat(exposure.portfolioPercentage()).isEqualByComparingTo("10.0000");
        assertThat(provider.assignedAgentsHolding(java.util.Set.of("SYN-SGX-001"))).containsExactly("agent-a");
        assertThat(provider.read(new HoldingsReadRequest(java.util.Set.of("SYN-SGX-001"), NOW, "agent-b"))
                .positions()).isEmpty();
    }

    @Test
    void expiredUnaffectedValuationBlocksEvaluationInsteadOfDisappearing() {
        put(ACCOUNT_1, "key-30000001", 0, position("SYN-SGX-001", "10", "1.0"), position("SYN-SGX-002", "90", "1.0"));
        jdbc.update("UPDATE current_position SET valued_at = ? WHERE instrument_id = 'SYN-SGX-002'",
                java.sql.Timestamp.from(NOW.minusSeconds(80 * 3600)));

        var result = provider.read(new HoldingsReadRequest(java.util.Set.of("SYN-SGX-001"), NOW, "agent-a"));

        assertThat(result.positions()).hasSize(2);
        assertThat(new ExposureCalculator().calculate(result.positions(), java.util.Set.of("SYN-SGX-001")).evaluable())
                .isFalse();
    }

    @Test
    void closedOrReassignedOrDeletedCrmRecordsStopTracking() {
        put(ACCOUNT_1, "key-40000001", 0, position("SYN-SGX-001", "10", "1.0"));
        var request = new HoldingsReadRequest(java.util.Set.of("SYN-SGX-001"), NOW, "agent-a");
        assertThat(provider.read(request).positions()).hasSize(1);

        crm.accounts.put(ACCOUNT_1, new AccountContext(ACCOUNT_1, CLIENT_1, "INVESTMENT", "CLOSED", "SGD", null));
        assertThat(provider.read(request).positions()).isEmpty();
        crm.accounts.put(ACCOUNT_1, new AccountContext(ACCOUNT_1, CLIENT_1, "INVESTMENT", "ACTIVE", "SGD", null));
        crm.owners.put(CLIENT_1, "agent-b");
        assertThat(provider.read(request).positions()).isEmpty();
        assertThat(provider.assignedAgentsHolding(java.util.Set.of("SYN-SGX-001"))).containsExactly("agent-b");

        crm.down = true; // unknown is not "no holders": fail closed
        assertThatThrownBy(() -> provider.assignedAgentsHolding(java.util.Set.of("SYN-SGX-001")))
                .isInstanceOf(DirectoryUnavailableException.class);
    }

    @Test
    void aggregateGaugesDescribeTheBookWithoutIdentifiers() {
        put(ACCOUNT_1, "key-45000001", 0, position("SYN-SGX-001", "10", "1.0"), position("SYN-SGX-002", "90", "1.0"));
        put(ACCOUNT_2, "key-45000002", 0, position("SYN-SGX-001", "5", "1.0"));
        jdbc.update("UPDATE current_position SET valued_at = ? WHERE instrument_id = 'SYN-SGX-002'",
                java.sql.Timestamp.from(NOW.minusSeconds(80 * 3600)));
        crm.accounts.put(ACCOUNT_2, new AccountContext(ACCOUNT_2, CLIENT_2, "INVESTMENT", "CLOSED", "SGD", null));

        reader.read(NOW, null, null);

        assertThat(meters.get("alert.holdings.portfolios.maintained").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("alert.holdings.positions.expired").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("alert.holdings.positions.missing").gauge().value()).isEqualTo(0.0);
        assertThat(meters.get("alert.holdings.crm.links.invalid").gauge().value()).isEqualTo(1.0);
        meters.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getValue()).doesNotContain(CLIENT_1.toString(), ACCOUNT_1.toString(), "agent-")));
    }

    @Test
    void groupsAlertsIntoOneEventCardAndFollowsReassignment() {
        put(ACCOUNT_1, "key-50000001", 0, position("SYN-SGX-001", "90", "1.0"), position("SYN-SGX-002", "10", "1.0"));
        put(ACCOUNT_2, "key-50000002", 0, position("SYN-SGX-001", "80", "1.0"), position("SYN-SGX-002", "20", "1.0"));
        AlertGenerationOutcome outcome = generate("agent-a");
        assertThat(outcome.alertsCreated()).isEqualTo(2);

        var cards = eventQueries.list("agent-a", false, 25).items();
        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().affectedClientCount()).isEqualTo(2);
        assertThat(cards.getFirst().summary()).contains("synthetic earnings");
        assertThat(eventQueries.list("agent-b", false, 25).items()).isEmpty();

        EventDetail detail = eventQueries.detail(cards.getFirst().eventId(), "agent-a");
        assertThat(detail.clients()).hasSize(2);
        assertThat(detail.clients()).extracting(c -> c.portfolioPercentage().intValue()).containsExactlyInAnyOrder(90, 80);
        assertThat(detail.clients()).extracting(c -> c.accountRef())
                .containsExactlyInAnyOrder(ACCOUNT_1.toString(), ACCOUNT_2.toString());

        // client 2 is reassigned to agent-b through the CRM
        crm.owners.put(CLIENT_2, "agent-b");
        var aCards = eventQueries.list("agent-a", false, 25).items();
        assertThat(aCards.getFirst().affectedClientCount()).isEqualTo(1);
        assertThat(eventQueries.list("agent-b", false, 25).items().getFirst().affectedClientCount()).isEqualTo(1);
        UUID alertOfClient2 = alerts.findAll().stream()
                .filter(a -> a.getClientRef().equals(CLIENT_2.toString())).findFirst().orElseThrow().getId();
        ownership.refresh();
        assertThat(alertList.findVisibleDetail(alertOfClient2, "agent-a", false)).isEmpty();
        assertThat(alertList.findVisibleDetail(alertOfClient2, "agent-b", false)).isPresent();
        assertThat(alertList.findVisible("agent-a", false, null, null, null, null, 10)).hasSize(1);

        // the visibility change is audited once, with opaque identifiers only (plan section 8)
        List<String> routing = jdbc.queryForList(
                "SELECT payload::text FROM audit_outbox WHERE event_type = 'alert.routing-changed'", String.class);
        assertThat(routing).hasSize(1);
        assertThat(routing.getFirst()).contains(CLIENT_2.toString(), "agent-a", "agent-b", "REASSIGNED")
                .doesNotContain("quantity", "valueSgd", "unitPrice");
        ownership.refresh(); // nothing changed since: no second event
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_outbox WHERE event_type = 'alert.routing-changed'", Long.class)).isOne();

        // the CRM cannot be reached: protected reads fail rather than reuse an old assignment
        crm.down = true;
        assertThatThrownBy(() -> eventQueries.list("agent-a", false, 25))
                .isInstanceOf(DirectoryUnavailableException.class);
    }

    @Test
    void appliesAnEventActionOnlyToTheSelectedCurrentlyOwnedClientsAndRecordsEachOutcome() {
        put(ACCOUNT_1, "key-60000001", 0, position("SYN-SGX-001", "90", "1.0"), position("SYN-SGX-002", "10", "1.0"));
        put(ACCOUNT_2, "key-60000002", 0, position("SYN-SGX-001", "80", "1.0"), position("SYN-SGX-002", "20", "1.0"));
        generate("agent-a");
        UUID eventId = eventQueries.list("agent-a", false, 25).items().getFirst().eventId();
        EventDetail detail = eventQueries.detail(eventId, "agent-a");
        var selected = detail.clients().getFirst();
        var unselected = detail.clients().getLast();

        ActionRequest inform = new ActionRequest(AlertDecisionType.INFORM_CLIENT, "call tomorrow",
                List.of(new ActionMember(selected.alertId(), selected.version())));
        ActionResult applied = eventActions.apply(eventId, "agent-a", "action-key-0001", UUID.randomUUID(), inform);
        assertThat(applied.members()).singleElement().satisfies(m -> {
            assertThat(m.outcome()).isEqualTo(MemberOutcome.APPLIED);
            assertThat(m.newStatus()).isEqualTo(AlertStatus.INFORMED);
        });
        assertThat(eventActions.apply(eventId, "agent-a", "action-key-0001", UUID.randomUUID(), inform).actionId())
                .isEqualTo(applied.actionId()); // idempotent replay
        assertThatThrownBy(() -> eventActions.apply(eventId, "agent-a", "action-key-0001", UUID.randomUUID(),
                new ActionRequest(AlertDecisionType.DISMISS, null,
                        List.of(new ActionMember(selected.alertId(), selected.version())))))
                .hasMessageContaining("idempotency");

        // the unselected client is untouched; a second action on the finished one is recorded, not applied
        assertThat(alerts.findById(unselected.alertId()).orElseThrow().getStatus()).isEqualTo(AlertStatus.NEW);
        ActionResult second = eventActions.apply(eventId, "agent-a", "action-key-0002", UUID.randomUUID(),
                new ActionRequest(AlertDecisionType.MONITOR, null, List.of(
                        new ActionMember(selected.alertId(), selected.version() + 1),
                        new ActionMember(unselected.alertId(), 99))));
        assertThat(second.members()).extracting(m -> m.outcome())
                .containsExactlyInAnyOrder(MemberOutcome.INVALID_TRANSITION, MemberOutcome.STALE_VERSION);
        assertThat(alerts.findById(unselected.alertId()).orElseThrow().getStatus()).isEqualTo(AlertStatus.NEW);

        // another agent can neither see the event nor act on its clients
        assertThatThrownBy(() -> eventActions.apply(eventId, "agent-b", "action-key-0003", UUID.randomUUID(), inform))
                .isInstanceOf(bank.alert.service.AlertNotFoundException.class);
        assertThat(outbox.findAll()).filteredOn(e -> e.getEventType().equals("alert.event-action-recorded"))
                .hasSize(2).allMatch(e -> e.getPayload().contains("\"clientContacted\":false"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_event_action_member", Long.class)).isEqualTo(3);
    }

    @Test
    void eachInstrumentIsScoredAgainstTheWholePortfolioAndANewRevisionKeepsOldEvidence() {
        put(ACCOUNT_1, "key-70000001", 0, position("SYN-SGX-001", "90", "1.0"), position("SYN-SGX-002", "10", "1.0"));
        AlertGenerationOutcome outcome = generate("agent-a");
        UUID alertId = outcome.alertIds().getFirst();
        put(ACCOUNT_1, "key-70000002", 1, position("SYN-SGX-001", "1", "1.0"), position("SYN-SGX-002", "99", "1.0"));

        assertThat(alertList.findVisibleDetail(alertId, "agent-a", false)).hasValueSatisfying(detail ->
        {
            assertThat(detail.portfolioPercentage()).isEqualByComparingTo("90.0000");
            assertThat(detail.accountRef()).isEqualTo(ACCOUNT_1.toString());
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_snapshot", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT portfolio_revision FROM holding_snapshot LIMIT 1", Long.class)).isOne();
    }

    @Test
    void aFullAuditBacklogRefusesTheWriteAndLeavesNothingBehind() {
        for (int i = 0; i < 100; i++) {
            outbox.save(new bank.alert.domain.AuditOutbox(UUID.randomUUID(), "alert.test-backlog", 1, "{}", NOW));
        }
        outbox.flush();

        assertThatThrownBy(() -> put(ACCOUNT_1, "key-86000001", 0, position("SYN-SGX-001", "10", "1.0")))
                .isInstanceOf(bank.alert.service.AuditUnavailableException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM current_portfolio", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM current_position", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_write_request", Long.class)).isZero();
    }

    private static final UUID ACCOUNT_3 = UUID.fromString("a0000000-0000-4000-8000-000000000004");

    private void secondAccountForClient1() {
        crm.accounts.put(ACCOUNT_3, new AccountContext(ACCOUNT_3, CLIENT_1, "INVESTMENT", "ACTIVE", "SGD", null));
    }

    @Test
    void sameInstrumentInTwoAccountsOfOneClientGivesOneAlertForTheHigherScoringAccount() {
        secondAccountForClient1();
        put(ACCOUNT_1, "key-80000001", 0, position("SYN-SGX-001", "30", "1.0"), position("SYN-SGX-002", "70", "1.0"));
        put(ACCOUNT_3, "key-80000002", 0, position("SYN-SGX-001", "80", "1.0"), position("SYN-SGX-002", "20", "1.0"));

        AlertGenerationOutcome outcome = generate("agent-a");

        assertThat(outcome.alertsCreated()).isOne(); // existing cardinality: one alert per client/event/instrument
        assertThat(alertList.findVisibleDetail(outcome.alertIds().getFirst(), "agent-a", false)).hasValueSatisfying(detail -> {
            assertThat(detail.accountRef()).isEqualTo(ACCOUNT_3.toString());
            assertThat(detail.portfolioPercentage()).isEqualByComparingTo("80.0000"); // not 30 + 80
        });
        // two accounts of one client are one affected client on the event card
        assertThat(eventQueries.list("agent-a", false, 25).items().getFirst().affectedClientCount()).isOne();
    }

    @Test
    void equalAccountScoresAreBrokenByTheLowerAccountId() {
        secondAccountForClient1();
        put(ACCOUNT_3, "key-81000001", 0, position("SYN-SGX-001", "50", "1.0"), position("SYN-SGX-002", "50", "1.0"));
        put(ACCOUNT_1, "key-81000002", 0, position("SYN-SGX-001", "50", "1.0"), position("SYN-SGX-002", "50", "1.0"));

        AlertGenerationOutcome outcome = generate("agent-a");

        assertThat(outcome.alertsCreated()).isOne();
        assertThat(alertList.findVisibleDetail(outcome.alertIds().getFirst(), "agent-a", false).orElseThrow().accountRef())
                .isEqualTo(ACCOUNT_1.toString());
    }

    @Test
    void fiveClientsOfOneAgentMakeOneCardAndAnotherAgentSeesOnlyItsOwn() {
        put(ACCOUNT_1, "key-82000001", 0, position("SYN-SGX-001", "90", "1.0"), position("SYN-SGX-002", "10", "1.0"));
        put(ACCOUNT_2, "key-82000002", 0, position("SYN-SGX-001", "80", "1.0"), position("SYN-SGX-002", "20", "1.0"));
        for (int i = 3; i <= 5; i++) addClientHolding(i, "agent-a", "key-8200000" + i);
        for (int i = 6; i <= 7; i++) addClientHolding(i, "agent-b", "key-8200000" + i);

        assertThat(generate("agent-a").alertsCreated()).isEqualTo(5);
        assertThat(generate("agent-b").alertsCreated()).isEqualTo(2);

        var aCards = eventQueries.list("agent-a", false, 25).items();
        assertThat(aCards).hasSize(1);
        assertThat(aCards.getFirst().affectedClientCount()).isEqualTo(5);
        var bCards = eventQueries.list("agent-b", false, 25).items();
        assertThat(bCards).hasSize(1);
        assertThat(bCards.getFirst().affectedClientCount()).isEqualTo(2);
        assertThat(aCards.getFirst().summary()).isEqualTo(bCards.getFirst().summary()); // one shared briefing
        var aClients = eventQueries.detail(aCards.getFirst().eventId(), "agent-a").clients();
        var bClients = eventQueries.detail(bCards.getFirst().eventId(), "agent-b").clients();
        assertThat(aClients).hasSize(5);
        assertThat(aClients).extracting(c -> c.alertId()).doesNotContainAnyElementsOf(
                bClients.stream().map(c -> c.alertId()).toList());
    }

    @Test
    void eventPagesUseAStableCursorAndRecheckCurrentOwnership() {
        put(ACCOUNT_1, "key-82500001", 0, position("SYN-SGX-001", "90", "1.0"), position("SYN-SGX-002", "10", "1.0"));
        generate("agent-a", 2);
        generate("agent-a", 3);

        var first = eventQueries.list("agent-a", false, null, 1);
        assertThat(first.items()).hasSize(1);
        assertThat(first.nextCursor()).isNotBlank();
        var second = eventQueries.list("agent-a", false, first.nextCursor(), 1);
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().eventId()).isNotEqualTo(first.items().getFirst().eventId());
        assertThat(second.nextCursor()).isNull();
        assertThat(eventQueries.list("agent-a", true, first.nextCursor(), 1).items())
                .extracting(card -> card.eventId()).containsExactly(second.items().getFirst().eventId());
        assertThatThrownBy(() -> eventQueries.list("agent-a", false, "bad-cursor", 1))
                .isInstanceOf(IllegalArgumentException.class);

        crm.owners.put(CLIENT_1, "agent-b");
        assertThat(eventQueries.list("agent-a", false, first.nextCursor(), 1).items()).isEmpty();
        assertThat(eventQueries.list("agent-b", false, null, 10).items()).hasSize(2);
    }

    @Test
    void aClientAffectedAfterAnInformIsNotTreatedAsInformed() {
        put(ACCOUNT_1, "key-83000001", 0, position("SYN-SGX-001", "90", "1.0"), position("SYN-SGX-002", "10", "1.0"));
        put(ACCOUNT_2, "key-83000002", 0, position("SYN-SGX-001", "80", "1.0"), position("SYN-SGX-002", "20", "1.0"));
        generate("agent-a");
        UUID eventId = eventQueries.list("agent-a", false, 25).items().getFirst().eventId();
        var informed = eventQueries.detail(eventId, "agent-a").clients().getFirst();
        eventActions.apply(eventId, "agent-a", "action-key-9001", UUID.randomUUID(), new ActionRequest(
                AlertDecisionType.INFORM_CLIENT, null, List.of(new ActionMember(informed.alertId(), informed.version()))));

        addClientHolding(3, "agent-a", "key-83000003"); // newly holds the instrument
        generate("agent-a");

        var clients = eventQueries.detail(eventId, "agent-a").clients();
        assertThat(clients).hasSize(3);
        assertThat(alerts.findById(informed.alertId()).orElseThrow().getStatus()).isEqualTo(AlertStatus.INFORMED);
        assertThat(clients).filteredOn(c -> !c.alertId().equals(informed.alertId())).allSatisfy(c ->
                assertThat(alerts.findById(c.alertId()).orElseThrow().getStatus()).isEqualTo(AlertStatus.NEW));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_event_action_member", Long.class)).isOne();
    }

    @Test
    void anOldCursorCannotRetrieveAlertsOfAReassignedClient() {
        put(ACCOUNT_1, "key-84000001", 0, position("SYN-SGX-001", "90", "1.0"), position("SYN-SGX-002", "10", "1.0"));
        put(ACCOUNT_2, "key-84000002", 0, position("SYN-SGX-001", "80", "1.0"), position("SYN-SGX-002", "20", "1.0"));
        generate("agent-a");
        var firstPage = alertList.findVisible("agent-a", false, null, null, null, null, 1);
        assertThat(firstPage).hasSize(1);
        var cursor = firstPage.getFirst();

        crm.owners.put(CLIENT_1, "agent-b");
        crm.owners.put(CLIENT_2, "agent-b");
        ownership.refresh();

        assertThat(alertList.findVisible("agent-a", false, null, null, cursor.createdAt(), cursor.id(), 10)).isEmpty();
        assertThat(alertList.findVisible("agent-a", false, null, null, null, null, 10)).isEmpty();
        assertThat(alertList.findVisible("agent-b", false, null, null, null, null, 10)).hasSize(2);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void twoConcurrentReplacementsOnTheSameRevisionLeaveExactlyOneWinner() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<HttpStatus>> results = new java.util.ArrayList<>();
            for (int writer = 0; writer < 2; writer++) {
                String key = "key-8500000" + writer;
                String quantity = writer == 0 ? "10" : "20";
                results.add(pool.submit(() -> {
                    start.await();
                    return statusOf(() -> put(ACCOUNT_1, key, 0, position("SYN-SGX-001", quantity, "1.0")));
                }));
            }
            start.countDown();
            List<HttpStatus> statuses = new java.util.ArrayList<>();
            for (var result : results) statuses.add(result.get(30, java.util.concurrent.TimeUnit.SECONDS));

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT revision FROM current_portfolio WHERE account_id = ?", Long.class,
                    ACCOUNT_1)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM current_position WHERE account_id = ?", Long.class,
                    ACCOUNT_1)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox WHERE event_type LIKE 'alert.portfolio%'",
                    Long.class)).isOne(); // the loser wrote nothing
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM current_position");
            jdbc.update("DELETE FROM portfolio_write_request");
            jdbc.update("DELETE FROM current_portfolio");
            jdbc.update("DELETE FROM audit_outbox");
        }
    }

    /** A further client with its own investment account and a 70/30 portfolio, owned by the given agent. */
    private void addClientHolding(int number, String agent, String key) {
        UUID client = UUID.fromString(String.format("c0000000-0000-4000-8000-%012d", number));
        UUID account = UUID.fromString(String.format("a0000000-0000-4000-8000-%012d", 100 + number));
        crm.accounts.put(account, new AccountContext(account, client, "INVESTMENT", "ACTIVE", "SGD", null));
        crm.owners.put(client, agent);
        portfolios.replace(account, agent, "user-token", key, UUID.randomUUID(), new PortfolioReplaceRequest(0, "manual-entry",
                List.of(position("SYN-SGX-001", "70", "1.0"), position("SYN-SGX-002", "30", "1.0"))));
    }

    private HttpStatus statusOf(Runnable action) {
        try {
            action.run();
        } catch (PortfolioApiException e) {
            return e.status();
        }
        return HttpStatus.OK;
    }

    private AlertGenerationOutcome generate(String agent) {
        return generate(agent, 2);
    }

    private AlertGenerationOutcome generate(String agent, int sourceNumber) {
        jdbc.update("""
                INSERT INTO search_run (id, window_from, window_to, status, created_at)
                VALUES ('70000000-0000-4000-8000-000000000001', '2026-10-29T20:00:00Z', '2026-10-30T00:00:00Z',
                        'SUCCEEDED', '2026-10-30T00:00:00Z') ON CONFLICT (id) DO NOTHING
                """);
        SourceResult source = new SourceResult(UUID.fromString(String.format("70000000-0000-4000-8000-%012d", sourceNumber)),
                UUID.fromString("70000000-0000-4000-8000-000000000001"), "fixture-result-" + sourceNumber,
                "https://example.invalid/synthetic-earnings-" + sourceNumber,
                String.valueOf(sourceNumber).repeat(64), "Synthetic issuer earnings update", "example.invalid",
                Instant.parse("2026-10-30T00:00:00Z"), NOW, "d".repeat(64), Instant.parse("2027-01-28T00:00:00Z"));
        if (sourceResults.findById(source.getId()).isEmpty()) sourceResults.saveAndFlush(source);
        EventClassification classification = new EventClassification("Synthetic earnings event",
                EventType.EARNINGS, EventDirection.POSITIVE, new BigDecimal("0.90"),
                List.of(new AffectedInstrument("SYN-SGX-001", source.getId().toString())),
                List.of(source.getId().toString()), "fixture-model-v1", "prompt-v1", NOW);
        AgentBriefing briefing = new AgentBriefing("Synthetic issuer update",
                "The issuer reported synthetic earnings for review.", "The fixture is intentionally synthetic.",
                List.of(source.getId().toString()), List.of("Review the cited source."), true,
                "fixture-model-v1", "prompt-v1", NOW);
        AlertGenerationService service = new AlertGenerationService(sourceResults, provider,
                new ExposureCalculator(), new MaterialityScorer(), holdingSnapshots, alerts, outbox,
                jdbc, mapper, clock, 100);
        return service.generate(new AlertGenerationRequest(source.getId(), classification, briefing, agent,
                new BigDecimal("0.90"), new BigDecimal("0.80"), NOW));
    }
}
