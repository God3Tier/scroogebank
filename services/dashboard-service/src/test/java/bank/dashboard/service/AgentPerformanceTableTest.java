package bank.dashboard.service;

import bank.dashboard.dto.AgentSortMetric;
import bank.dashboard.service.AgentPerformanceTable.AgentPerformance;
import bank.dashboard.service.AgentPerformanceTable.AgentRow;
import bank.dashboard.upstream.dto.AccountStats;
import bank.dashboard.upstream.dto.AgentSummary;
import bank.dashboard.upstream.dto.ClientStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPerformanceTableTest {

    private static final List<AgentSummary> ROSTER = List.of(
            agent("sub-ada", "Ada", true),
            agent("sub-ben", "Ben", true),
            agent("sub-cai", "Cai", false),
            agent("sub-dee", "Dee", false));

    private static final ClientStats CLIENTS = new ClientStats(0, Map.of(), 0, List.of(), List.of(
            new ClientStats.AgentClientCounts("sub-ada", 2, 1, 1),
            new ClientStats.AgentClientCounts("sub-cai", 4, 4, 0),
            // An admin who created a client is not on the agent roster.
            new ClientStats.AgentClientCounts("sub-admin", 1, 0, 1)));

    private static final AccountStats ACCOUNTS = new AccountStats(0, Map.of(), Map.of(), Map.of(), Map.of(), 0,
            BigDecimal.ZERO, "SGD", List.of(), List.of(
            new AccountStats.AgentAccountCounts("sub-ben", 3, new BigDecimal("900.00")),
            new AccountStats.AgentAccountCounts("sub-ada", 1, new BigDecimal("100.00"))));

    @Test
    void joinsNamesToFiguresBySub() {
        AgentPerformance table = AgentPerformanceTable.build(ROSTER, CLIENTS, ACCOUNTS, AgentSortMetric.CLIENTS_ONBOARDED);

        AgentRow ada = row(table, "sub-ada");
        assertThat(ada.name()).isEqualTo("Ada Lim");
        assertThat(ada.clientsOnboarded()).isEqualTo(2);
        assertThat(ada.verificationRate()).isEqualTo(0.5);
        assertThat(ada.accountsOpened()).isEqualTo(1);
        assertThat(ada.bookedInitialDeposits()).isEqualByComparingTo("100.00");
        assertThat(table.currency()).isEqualTo("SGD");
        assertThat(table.sortedBy()).isEqualTo("clientsOnboarded");
    }

    @Test
    void listsEnabledAgentsAndAnyoneWithFiguresOnly() {
        AgentPerformance table = AgentPerformanceTable.build(ROSTER, CLIENTS, ACCOUNTS, AgentSortMetric.CLIENTS_ONBOARDED);

        // Dee is disabled and did nothing; Cai is disabled but onboarded clients.
        assertThat(table.rows()).extracting(AgentRow::agentId)
                .containsExactlyInAnyOrder("sub-ada", "sub-ben", "sub-cai", "sub-admin");
        assertThat(row(table, "sub-cai").enabled()).isFalse();
        assertThat(row(table, "sub-admin").name()).isNull();
    }

    @Test
    void anAgentWithNoClientsHasNoVerificationRate() {
        AgentPerformance table = AgentPerformanceTable.build(ROSTER, CLIENTS, ACCOUNTS, AgentSortMetric.CLIENTS_ONBOARDED);

        AgentRow ben = row(table, "sub-ben");
        assertThat(ben.clientsOnboarded()).isZero();
        assertThat(ben.verificationRate()).isNull();
    }

    @Test
    void sortsByTheChosenMetricHighestFirst() {
        assertThat(AgentPerformanceTable.build(ROSTER, CLIENTS, ACCOUNTS, AgentSortMetric.CLIENTS_ONBOARDED).rows())
                .extracting(AgentRow::agentId).containsExactly("sub-cai", "sub-ada", "sub-admin", "sub-ben");
        assertThat(AgentPerformanceTable.build(ROSTER, CLIENTS, ACCOUNTS, AgentSortMetric.BOOKED_INITIAL_DEPOSITS).rows())
                .extracting(AgentRow::agentId).startsWith("sub-ben", "sub-ada");
        // Ben has no rate, so he sorts after everyone who has one.
        assertThat(AgentPerformanceTable.build(ROSTER, CLIENTS, ACCOUNTS, AgentSortMetric.VERIFICATION_RATE).rows())
                .extracting(AgentRow::agentId).containsExactly("sub-cai", "sub-ada", "sub-admin", "sub-ben");
    }

    private static AgentRow row(AgentPerformance table, String agentId) {
        return table.rows().stream().filter(row -> row.agentId().equals(agentId)).findFirst().orElseThrow();
    }

    private static AgentSummary agent(String sub, String firstName, boolean enabled) {
        return new AgentSummary("id-" + sub, sub, firstName, "Lim", firstName.toLowerCase() + "@x.com", "AGENT", enabled);
    }
}
