package bank.dashboard.service;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.AgentSortMetric;
import bank.dashboard.dto.DashboardResponse;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.dto.Panel;
import bank.dashboard.dto.PanelReason;
import bank.dashboard.dto.PanelStatus;
import bank.dashboard.exception.UpstreamUnavailableException;
import bank.dashboard.security.CurrentUser;
import bank.dashboard.service.DashboardService.AccountsPerClient;
import bank.dashboard.service.DashboardService.AgentCount;
import bank.dashboard.upstream.AccountStatsPort;
import bank.dashboard.upstream.ClientStatsPort;
import bank.dashboard.upstream.TransactionStatsPort;
import bank.dashboard.upstream.UserRosterPort;
import bank.dashboard.upstream.dto.AccountStats;
import bank.dashboard.upstream.dto.AgentSummary;
import bank.dashboard.upstream.dto.ClientStats;
import bank.dashboard.upstream.dto.TeamComparison;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    private static final DashboardWindow WINDOW = new DashboardWindow(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

    private final ClientStatsPort clients = mock(ClientStatsPort.class);
    private final AccountStatsPort accounts = mock(AccountStatsPort.class);
    private final UserRosterPort roster = mock(UserRosterPort.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private DashboardService service;

    @BeforeEach
    void setUp() {
        DashboardProperties properties = new DashboardProperties(
                new DashboardProperties.Window(30, 365),
                new DashboardProperties.Cache(60, 1000),
                new DashboardProperties.Upstream(2000, 3000, "", "", "", ""));
        service = new DashboardService(clients, accounts, mock(TransactionStatsPort.class), roster,
                new PanelAssembler(properties), new DashboardCache(properties), currentUser,
                Clock.fixed(Instant.parse("2026-09-30T04:00:00Z"), ZoneOffset.UTC));
        when(currentUser.subject()).thenReturn("sub-1");
    }

    @Test
    void dividesAccountsByClients() {
        when(clients.stats(WINDOW, false)).thenReturn(clientStats(4));
        when(accounts.stats(WINDOW, false)).thenReturn(accountStats(6));

        DashboardResponse response = service.portfolio(WINDOW).response();

        assertThat(response.panels().get("accountsPerClient").data()).isEqualTo(new AccountsPerClient(1.5));
    }

    @Test
    void hasNoRatioWithoutClients() {
        when(clients.stats(WINDOW, false)).thenReturn(clientStats(0));
        when(accounts.stats(WINDOW, false)).thenReturn(accountStats(0));

        DashboardResponse response = service.portfolio(WINDOW).response();

        assertThat(response.panels().get("accountsPerClient").data()).isEqualTo(new AccountsPerClient(null));
    }

    @Test
    void theRatioIsUnavailableWhenEitherSideIs() {
        when(clients.stats(WINDOW, false)).thenReturn(clientStats(4));
        when(accounts.stats(WINDOW, false))
                .thenThrow(new UpstreamUnavailableException(PanelReason.UPSTREAM_FORBIDDEN, "refused"));

        DashboardResponse response = service.portfolio(WINDOW).response();

        assertThat(response.panels().get("clients").status()).isEqualTo(PanelStatus.OK);
        assertThat(response.panels().get("accountsPerClient").status()).isEqualTo(PanelStatus.UNAVAILABLE);
        assertThat(response.panels().get("accountsPerClient").reason()).isEqualTo(PanelReason.UPSTREAM_FORBIDDEN);
    }

    @Test
    void anAgentsOverviewComparesThemWithTheTeam() {
        // WINDOW is the last 30 days: the clock says 30 September in Singapore.
        when(clients.teamComparison(30)).thenReturn(new TeamComparison(30, "2026-09-01", "2026-09-30", 3, 2.0, 6));

        DashboardResponse response = service.overview(WINDOW).response();

        assertThat(response.panels().get("teamComparison").data())
                .isEqualTo(new TeamComparison(30, "2026-09-01", "2026-09-30", 3, 2.0, 6));
        assertThat(response.panels()).doesNotContainKey("agents");
    }

    @Test
    void dropsAMedianFromAGroupTooSmallEvenIfClientServiceSentOne() {
        when(clients.teamComparison(30)).thenReturn(new TeamComparison(30, "2026-09-01", "2026-09-30", 3, 2.0, 4));

        DashboardResponse response = service.overview(WINDOW).response();

        assertThat(((TeamComparison) response.panels().get("teamComparison").data()).teamMedian()).isNull();
    }

    @Test
    void comparesOnlyOverThePresetWindows() {
        DashboardWindow twentyNineDays = new DashboardWindow(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 30));
        DashboardWindow endingYesterday = new DashboardWindow(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 29));

        for (DashboardWindow window : List.of(twentyNineDays, endingYesterday)) {
            Panel panel = service.overview(window).response().panels().get("teamComparison");

            assertThat(panel.status()).isEqualTo(PanelStatus.UNAVAILABLE);
            assertThat(panel.reason()).isEqualTo(PanelReason.UNSUPPORTED_WINDOW);
        }
        verify(clients, never()).teamComparison(anyInt());
    }

    @Test
    void anAdminsOverviewCountsAgentsWithoutListingThem() {
        when(currentUser.isAdmin()).thenReturn(true);
        when(roster.agents()).thenReturn(List.of(
                new AgentSummary("1", "sub-a", "Ada", "Lim", "ada@x.com", "AGENT", true),
                new AgentSummary("2", "sub-b", "Ben", "Chua", "ben@x.com", "AGENT", false)));

        DashboardResponse response = service.overview(WINDOW).response();

        assertThat(response.panels().get("agents").data()).isEqualTo(new AgentCount(2, 1));
        assertThat(response.panels()).doesNotContainKey("teamComparison");
    }

    @Test
    void theAgentsTableFetchesItsThreeInputsInParallel() {
        // One after another, three cold calls shared one call's budget and
        // timed out after a restart. In parallel they take the slowest one.
        when(roster.agents()).thenAnswer(slowly(400, List.of(
                new AgentSummary("1", "sub-a", "Ada", "Lim", "ada@x.com", "AGENT", true))));
        when(clients.stats(WINDOW, true)).thenAnswer(slowly(400, agentClientStats()));
        when(accounts.stats(WINDOW, true)).thenAnswer(slowly(400, agentAccountStats()));

        long startedAt = System.nanoTime();
        DashboardResponse response = service.agentPerformance(WINDOW, AgentSortMetric.CLIENTS_ONBOARDED, "SGD").response();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(response.panels().get("agents").status()).isEqualTo(PanelStatus.OK);
        assertThat(elapsedMs).describedAs("three 400 ms calls in parallel").isLessThan(1_000);
    }

    @Test
    void theAgentsTableReturnsOnlyTheJoinedPanel() {
        when(clients.stats(WINDOW, true)).thenReturn(agentClientStats());
        when(accounts.stats(WINDOW, true)).thenReturn(agentAccountStats());

        DashboardResponse response = service.agentPerformance(WINDOW, AgentSortMetric.CLIENTS_ONBOARDED, "SGD").response();

        assertThat(response.panels()).containsOnlyKeys("agents");
    }

    @Test
    void theAgentsTableIsCachedPerCurrency() {
        // A ranking in USD is a different answer from one in SGD.
        when(clients.stats(WINDOW, true)).thenReturn(agentClientStats());
        when(accounts.stats(WINDOW, true)).thenReturn(agentAccountStats());

        service.agentPerformance(WINDOW, AgentSortMetric.BOOKED_INITIAL_DEPOSITS, "SGD");
        boolean sameCurrencyWasAHit = service.agentPerformance(WINDOW, AgentSortMetric.BOOKED_INITIAL_DEPOSITS, "SGD").cacheHit();
        ServedResponse usd = service.agentPerformance(WINDOW, AgentSortMetric.BOOKED_INITIAL_DEPOSITS, "USD");

        assertThat(sameCurrencyWasAHit).isTrue();
        assertThat(usd.cacheHit()).isFalse();
        assertThat(((AgentPerformanceTable.AgentPerformance) usd.response().panels().get("agents").data()).sortCurrency())
                .isEqualTo("USD");
    }

    @Test
    void theAgentsTableIsUnavailableWhenAnyInputIs() {
        when(clients.stats(WINDOW, true)).thenReturn(agentClientStats());
        when(accounts.stats(WINDOW, true))
                .thenThrow(new UpstreamUnavailableException(PanelReason.UPSTREAM_ERROR, "account-service returned 500"));

        Panel agents = service.agentPerformance(WINDOW, AgentSortMetric.CLIENTS_ONBOARDED, "SGD").response().panels().get("agents");

        assertThat(agents.status()).isEqualTo(PanelStatus.UNAVAILABLE);
        assertThat(agents.reason()).isEqualTo(PanelReason.UPSTREAM_ERROR);
    }

    @Test
    void anAnswerTheAgentsTableCannotJoinCostsThePanelNotTheRequest() {
        // As when the join ran inside the call: an upstream error, not a 500.
        when(roster.agents()).thenReturn(null);
        when(clients.stats(WINDOW, true)).thenReturn(agentClientStats());
        when(accounts.stats(WINDOW, true)).thenReturn(agentAccountStats());

        Panel agents = service.agentPerformance(WINDOW, AgentSortMetric.CLIENTS_ONBOARDED, "SGD").response().panels().get("agents");

        assertThat(agents.status()).isEqualTo(PanelStatus.UNAVAILABLE);
        assertThat(agents.reason()).isEqualTo(PanelReason.UPSTREAM_ERROR);
    }

    private static <T> Answer<T> slowly(long millis, T answer) {
        return invocation -> {
            Thread.sleep(millis);
            return answer;
        };
    }

    private static ClientStats agentClientStats() {
        return new ClientStats(1, Map.of(), 1, List.of(), List.of(new ClientStats.AgentClientCounts("sub-a", 1, 1, 0)));
    }

    private static AccountStats agentAccountStats() {
        return new AccountStats(1, Map.of(), Map.of(), Map.of(), Map.of(), 1, Map.of("SGD", BigDecimal.TEN), List.of("SGD"),
                List.of(), List.of(new AccountStats.AgentAccountCounts("sub-a", 1, Map.of("SGD", BigDecimal.TEN))));
    }

    private static ClientStats clientStats(long total) {
        return new ClientStats(total, Map.of(), 0, List.of(), null);
    }

    private static AccountStats accountStats(long total) {
        return new AccountStats(total, Map.of(), Map.of(), Map.of(), Map.of(), 0, Map.of(), List.of(), List.of(), null);
    }
}
