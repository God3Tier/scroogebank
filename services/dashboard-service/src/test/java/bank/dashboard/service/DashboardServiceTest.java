package bank.dashboard.service;

import bank.dashboard.config.DashboardProperties;
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
                new DashboardProperties.Upstream(2000, 3000, "", "", ""));
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
                .thenThrow(new UpstreamUnavailableException(PanelReason.MIXED_CURRENCIES, "two currencies"));

        DashboardResponse response = service.portfolio(WINDOW).response();

        assertThat(response.panels().get("clients").status()).isEqualTo(PanelStatus.OK);
        assertThat(response.panels().get("accountsPerClient").status()).isEqualTo(PanelStatus.UNAVAILABLE);
        assertThat(response.panels().get("accountsPerClient").reason()).isEqualTo(PanelReason.MIXED_CURRENCIES);
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

    private static ClientStats clientStats(long total) {
        return new ClientStats(total, Map.of(), 0, List.of(), null);
    }

    private static AccountStats accountStats(long total) {
        return new AccountStats(total, Map.of(), Map.of(), Map.of(), Map.of(), 0, BigDecimal.ZERO, null, List.of(), null);
    }
}
