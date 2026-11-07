package bank.dashboard.upstream.http;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.security.CurrentUser;
import bank.dashboard.upstream.dto.AccountStats;
import bank.dashboard.upstream.dto.ClientActionItems;
import bank.dashboard.upstream.dto.ClientStats;
import bank.dashboard.upstream.dto.DailyCount;
import bank.dashboard.upstream.dto.TeamComparison;
import bank.dashboard.upstream.dto.TransactionStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static bank.dashboard.contract.OpenApiContract.upstream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The client, account and transaction adapters against the JSON the Go
 * services actually return.
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
                new DashboardProperties.Upstream(2000, 3000, "http://user-service", "http://client-service", "http://account-service",
                        "http://transaction-service"));
        clients = new HttpClientStatsAdapter(upstream, properties);
        accounts = new HttpAccountStatsAdapter(upstream, properties);
        transactions = new HttpTransactionStatsAdapter(upstream, properties);
    }

    @Test
    void readsClientFigures() {
        server.expect(requestTo("http://client-service/aggregates/clients/stats?from=2026-09-01&to=2026-09-30&groupBy=agent"))
                .andExpect(header("Authorization", "Bearer the-callers-token"))
                .andRespond(upstream("dashboard-aggregates.yaml", withSuccess("""
                        {"total":6,"byVerificationStatus":{"UNVERIFIED":4,"VERIFIED":2},"createdInWindow":4,
                         "dailyCreated":[{"date":"2026-09-02","count":2}],
                         "byAgent":[{"agentId":"sub-a","clientsOnboarded":2,"verified":1,"unverified":1}]}
                        """, MediaType.APPLICATION_JSON)));

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
                .andRespond(upstream("dashboard-aggregates.yaml", withSuccess("{\"total\":0,\"byVerificationStatus\":{},\"createdInWindow\":0,\"dailyCreated\":[]}",
                        MediaType.APPLICATION_JSON)));

        assertThat(clients.stats(SEPTEMBER, false).byAgent()).isNull();
        server.verify();
    }

    @Test
    void readsTheWorkQueue() {
        server.expect(requestTo("http://client-service/aggregates/clients/action-items?staleDays=7"))
                .andRespond(upstream("dashboard-aggregates.yaml", withSuccess("""
                        {"staleUnverified":[{"clientId":"00000000-0000-4000-8000-0000000000c1","firstName":"Ann","lastName":"Tan","createdAt":"2026-09-02T02:00:00Z"}],
                         "staleUnverifiedTotal":3}
                        """, MediaType.APPLICATION_JSON)));

        ClientActionItems items = clients.actionItems(7);

        assertThat(items.staleUnverifiedTotal()).isEqualTo(3);
        assertThat(items.staleUnverified()).singleElement().extracting(ClientActionItems.ClientRef::firstName).isEqualTo("Ann");
    }

    @Test
    void readsTheTeamComparison() {
        server.expect(requestTo("http://client-service/aggregates/clients/team-comparison?days=30"))
                .andExpect(header("Authorization", "Bearer the-callers-token"))
                .andRespond(upstream("dashboard-aggregates.yaml", withSuccess("""
                        {"days":30,"from":"2026-09-01","to":"2026-09-30","clientsOnboarded":3,"teamMedian":null,"cohortSize":4}
                        """, MediaType.APPLICATION_JSON)));

        TeamComparison comparison = clients.teamComparison(30);

        assertThat(comparison.clientsOnboarded()).isEqualTo(3);
        assertThat(comparison.teamMedian()).isNull();
        assertThat(comparison.cohortSize()).isEqualTo(4);
    }

    @Test
    void readsAccountFiguresWithExactAmounts() {
        server.expect(requestTo("http://account-service/aggregates/accounts/stats?from=2026-09-01&to=2026-09-30"))
                .andRespond(upstream("dashboard-aggregates.yaml", withSuccess("""
                        {"total":3,"byType":{"savings":2},"byStatus":{"active":3},"byCurrency":{"SGD":3},"byBranch":{"B1":3},
                         "openedInWindow":2,"bookedInitialDeposits":{"SGD":"350.50"},"currencies":["SGD"],
                         "dailyOpened":[{"date":"2026-09-01","count":1,"value":"100.00"}]}
                        """, MediaType.APPLICATION_JSON)));

        AccountStats stats = accounts.stats(SEPTEMBER, false);

        assertThat(stats.bookedInitialDeposits().get("SGD")).isEqualByComparingTo(new BigDecimal("350.50"));
        assertThat(stats.currencies()).containsExactly("SGD");
        assertThat(stats.dailyOpened().get(0).value()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void readsAccountFiguresInMoreThanOneCurrency() {
        // Each currency's deposits on their own, and no day's value (ADR-0062).
        server.expect(requestTo("http://account-service/aggregates/accounts/stats?from=2026-09-01&to=2026-09-30"))
                .andRespond(upstream("dashboard-aggregates.yaml", withSuccess("""
                        {"total":2,"byType":{"savings":2},"byStatus":{"active":2},"byCurrency":{"SGD":1,"USD":1},"byBranch":{"B1":2},
                         "openedInWindow":2,"bookedInitialDeposits":{"SGD":"7.00","USD":"5.00"},"currencies":["SGD","USD"],
                         "dailyOpened":[{"date":"2026-09-01","count":2}]}
                        """, MediaType.APPLICATION_JSON)));

        AccountStats stats = accounts.stats(SEPTEMBER, false);

        assertThat(stats.bookedInitialDeposits()).containsOnlyKeys("SGD", "USD");
        assertThat(stats.bookedInitialDeposits().get("USD")).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(stats.currencies()).containsExactly("SGD", "USD");
        assertThat(stats.dailyOpened().get(0).value()).isNull();
    }

    @Test
    void readsTransactionFiguresFromTransactionService() {
        server.expect(requestTo("http://transaction-service/aggregates/transactions/stats?from=2026-09-01&to=2026-09-30"))
                .andExpect(header("Authorization", "Bearer the-callers-token"))
                .andRespond(upstream("dashboard-aggregates.yaml", withSuccess("""
                        {"total":5,"byStatus":{"Completed":3},"byType":{"Deposit":4},"currencies":["SGD"],
                         "totalValue":"156.50","daily":[],"pendingBacklog":1,"unmatched":1}
                        """, MediaType.APPLICATION_JSON)));

        TransactionStats stats = transactions.stats(SEPTEMBER);

        assertThat(stats.totalValue()).isEqualByComparingTo(new BigDecimal("156.50"));
        assertThat(stats.unmatched()).isEqualTo(1L);
        assertThat(stats.pendingBacklog()).isEqualTo(1);
    }

    @Test
    void leavesTheValueEmptyWhenTransactionsSpanCurrencies() {
        server.expect(requestTo("http://transaction-service/aggregates/transactions/stats?from=2026-09-01&to=2026-09-30"))
                .andRespond(upstream("dashboard-aggregates.yaml", withSuccess("""
                        {"total":2,"byStatus":{},"byType":{},"currencies":["SGD","USD"],"daily":[],"pendingBacklog":0}
                        """, MediaType.APPLICATION_JSON)));

        TransactionStats stats = transactions.stats(SEPTEMBER);

        assertThat(stats.totalValue()).isNull();
        assertThat(stats.unmatched()).isNull();
    }
}
