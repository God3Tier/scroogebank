package bank.dashboard.upstream.http;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.dto.PanelReason;
import bank.dashboard.exception.UpstreamUnavailableException;
import bank.dashboard.security.CurrentUser;
import bank.dashboard.upstream.dto.AccountStats;
import bank.dashboard.upstream.dto.ClientActionItems;
import bank.dashboard.upstream.dto.ClientStats;
import bank.dashboard.upstream.dto.DailyCount;
import bank.dashboard.upstream.dto.TeamComparison;
import bank.dashboard.upstream.dto.TransactionStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The client, account and transaction adapters against the JSON the Go
 * services and user-service actually return.
 */
class HttpStatsAdaptersTest {

    private static final DashboardWindow SEPTEMBER = new DashboardWindow(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

    private MockRestServiceServer server;
    private HttpClientStatsAdapter clients;
    private HttpAccountStatsAdapter accounts;
    private HttpTransactionStatsAdapter transactions;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.tokenValue()).thenReturn("the-callers-token");
        UpstreamClient upstream = new UpstreamClient(builder.build(), currentUser);
        DashboardProperties properties = new DashboardProperties(
                new DashboardProperties.Window(30, 365),
                new DashboardProperties.Cache(60, 1000),
                new DashboardProperties.Upstream(2000, 3000, "http://user-service", "http://client-service", "http://account-service"));
        clients = new HttpClientStatsAdapter(upstream, properties);
        accounts = new HttpAccountStatsAdapter(upstream, properties);
        transactions = new HttpTransactionStatsAdapter(upstream, properties);
    }

    @Test
    void readsClientFigures() {
        server.expect(requestTo("http://client-service/aggregates/clients/stats?from=2026-09-01&to=2026-09-30&groupBy=agent"))
                .andExpect(header("Authorization", "Bearer the-callers-token"))
                .andRespond(withSuccess("""
                        {"total":6,"byVerificationStatus":{"UNVERIFIED":4,"VERIFIED":2},"createdInWindow":4,
                         "dailyCreated":[{"date":"2026-09-02","count":2}],
                         "byAgent":[{"agentId":"sub-a","clientsOnboarded":2,"verified":1,"unverified":1}]}
                        """, MediaType.APPLICATION_JSON));

        ClientStats stats = clients.stats(SEPTEMBER, true);

        assertThat(stats.total()).isEqualTo(6);
        assertThat(stats.byVerificationStatus()).containsEntry("VERIFIED", 2L);
        assertThat(stats.dailyCreated()).containsExactly(new DailyCount(LocalDate.of(2026, 9, 2), 2, null));
        assertThat(stats.byAgent()).containsExactly(new ClientStats.AgentClientCounts("sub-a", 2, 1, 1));
        server.verify();
    }

    @Test
    void asksForPerAgentClientFiguresOnlyWhenTold() {
        server.expect(requestTo("http://client-service/aggregates/clients/stats?from=2026-09-01&to=2026-09-30"))
                .andRespond(withSuccess("{\"total\":0,\"byVerificationStatus\":{},\"createdInWindow\":0,\"dailyCreated\":[]}",
                        MediaType.APPLICATION_JSON));

        assertThat(clients.stats(SEPTEMBER, false).byAgent()).isNull();
        server.verify();
    }

    @Test
    void readsTheWorkQueue() {
        server.expect(requestTo("http://client-service/aggregates/clients/action-items?staleDays=7"))
                .andRespond(withSuccess("""
                        {"staleUnverified":[{"clientId":"c1","firstName":"Ann","lastName":"Tan","createdAt":"2026-09-02T02:00:00Z"}],
                         "staleUnverifiedTotal":3}
                        """, MediaType.APPLICATION_JSON));

        ClientActionItems items = clients.actionItems(7);

        assertThat(items.staleUnverifiedTotal()).isEqualTo(3);
        assertThat(items.staleUnverified()).singleElement().extracting(ClientActionItems.ClientRef::firstName).isEqualTo("Ann");
    }

    @Test
    void readsTheTeamComparison() {
        server.expect(requestTo("http://client-service/aggregates/clients/team-comparison?days=30"))
                .andExpect(header("Authorization", "Bearer the-callers-token"))
                .andRespond(withSuccess("""
                        {"days":30,"from":"2026-09-01","to":"2026-09-30","clientsOnboarded":3,"teamMedian":null,"cohortSize":4}
                        """, MediaType.APPLICATION_JSON));

        TeamComparison comparison = clients.teamComparison(30);

        assertThat(comparison.clientsOnboarded()).isEqualTo(3);
        assertThat(comparison.teamMedian()).isNull();
        assertThat(comparison.cohortSize()).isEqualTo(4);
    }

    @Test
    void readsAccountFiguresWithExactAmounts() {
        server.expect(requestTo("http://account-service/aggregates/accounts/stats?from=2026-09-01&to=2026-09-30"))
                .andRespond(withSuccess("""
                        {"total":3,"byType":{"savings":2},"byStatus":{"active":3},"byCurrency":{"SGD":3},"byBranch":{"B1":3},
                         "openedInWindow":2,"bookedInitialDeposits":"350.50","currency":"SGD",
                         "dailyOpened":[{"date":"2026-09-01","count":1,"value":"100.00"}]}
                        """, MediaType.APPLICATION_JSON));

        AccountStats stats = accounts.stats(SEPTEMBER, false);

        assertThat(stats.bookedInitialDeposits()).isEqualByComparingTo(new BigDecimal("350.50"));
        assertThat(stats.currency()).isEqualTo("SGD");
        assertThat(stats.dailyOpened().get(0).value()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void reportsMixedCurrenciesRatherThanAWrongTotal() {
        server.expect(requestTo("http://account-service/aggregates/accounts/stats?from=2026-09-01&to=2026-09-30"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> accounts.stats(SEPTEMBER, false))
                .isInstanceOf(UpstreamUnavailableException.class)
                .extracting(ex -> ((UpstreamUnavailableException) ex).reason())
                .isEqualTo(PanelReason.MIXED_CURRENCIES);
    }

    @Test
    void readsTransactionFiguresFromUserService() {
        server.expect(requestTo("http://user-service/aggregates/transactions/stats?from=2026-09-01&to=2026-09-30"))
                .andExpect(header("Authorization", "Bearer the-callers-token"))
                .andRespond(withSuccess("""
                        {"total":5,"byStatus":{"Completed":3},"byType":{"Deposit":4},"currencies":["SGD"],
                         "totalValue":"156.50","daily":[],"pendingBacklog":1,"unmatched":1}
                        """, MediaType.APPLICATION_JSON));

        TransactionStats stats = transactions.stats(SEPTEMBER);

        assertThat(stats.totalValue()).isEqualByComparingTo(new BigDecimal("156.50"));
        assertThat(stats.unmatched()).isEqualTo(1L);
        assertThat(stats.pendingBacklog()).isEqualTo(1);
    }

    @Test
    void leavesTheValueEmptyWhenTransactionsSpanCurrencies() {
        server.expect(requestTo("http://user-service/aggregates/transactions/stats?from=2026-09-01&to=2026-09-30"))
                .andRespond(withSuccess("""
                        {"total":2,"byStatus":{},"byType":{},"currencies":["SGD","USD"],"daily":[],"pendingBacklog":0}
                        """, MediaType.APPLICATION_JSON));

        TransactionStats stats = transactions.stats(SEPTEMBER);

        assertThat(stats.totalValue()).isNull();
        assertThat(stats.unmatched()).isNull();
    }
}
