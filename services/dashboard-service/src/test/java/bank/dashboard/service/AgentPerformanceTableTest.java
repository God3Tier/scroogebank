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

    // Ben opened the most in SGD, Ada the only USD account (ADR-0062).
    private static final AccountStats ACCOUNTS = new AccountStats(0, Map.of(), Map.of(), Map.of(), Map.of(), 0,
            Map.of(), List.of("SGD", "USD"), List.of(), List.of(
            new AccountStats.AgentAccountCounts("sub-ben", 3, Map.of("SGD", new BigDecimal("900.00"))),
            new AccountStats.AgentAccountCounts("sub-ada", 2,
                    Map.of("SGD", new BigDecimal("100.00"), "USD", new BigDecimal("5.00")))));

    @Test
    void joinsNamesToFiguresBySub() {
        AgentPerformance table = build(AgentSortMetric.CLIENTS_ONBOARDED, "SGD");

        AgentRow ada = row(table, "sub-ada");
        assertThat(ada.name()).isEqualTo("Ada Lim");
        assertThat(ada.clientsOnboarded()).isEqualTo(2);
        assertThat(ada.verificationRate()).isEqualTo(0.5);
        assertThat(ada.accountsOpened()).isEqualTo(2);
        assertThat(ada.bookedInitialDeposits())
                .containsOnlyKeys("SGD", "USD")
                .hasEntrySatisfying("USD", amount -> assertThat(amount).isEqualByComparingTo("5.00"));
        assertThat(table.currencies()).containsExactly("SGD", "USD");
        assertThat(table.sortCurrency()).isEqualTo("SGD");
        assertThat(table.sortedBy()).isEqualTo("clientsOnboarded");
    }

    @Test
    void listsEnabledAgentsAndAnyoneWithFiguresOnly() {
        AgentPerformance table = build(AgentSortMetric.CLIENTS_ONBOARDED, "SGD");

        // Dee is disabled and did nothing; Cai is disabled but onboarded clients.
        assertThat(table.rows()).extracting(AgentRow::agentId)
                .containsExactlyInAnyOrder("sub-ada", "sub-ben", "sub-cai", "sub-admin");
        assertThat(row(table, "sub-cai").enabled()).isFalse();
        assertThat(row(table, "sub-admin").name()).isNull();
    }

    @Test
    void anAgentWithNoClientsHasNoVerificationRate() {
        AgentPerformance table = build(AgentSortMetric.CLIENTS_ONBOARDED, "SGD");

        AgentRow ben = row(table, "sub-ben");
        assertThat(ben.clientsOnboarded()).isZero();
        assertThat(ben.verificationRate()).isNull();
    }

    @Test
    void sortsByTheChosenMetricHighestFirst() {
        assertThat(build(AgentSortMetric.CLIENTS_ONBOARDED, "SGD").rows())
                .extracting(AgentRow::agentId).containsExactly("sub-cai", "sub-ada", "sub-admin", "sub-ben");
        assertThat(build(AgentSortMetric.BOOKED_INITIAL_DEPOSITS, "SGD").rows())
                .extracting(AgentRow::agentId).startsWith("sub-ben", "sub-ada");
        // Ben has no rate, so he sorts after everyone who has one.
        assertThat(build(AgentSortMetric.VERIFICATION_RATE, "SGD").rows())
                .extracting(AgentRow::agentId).containsExactly("sub-cai", "sub-ada", "sub-admin", "sub-ben");
    }

    @Test
    void ranksDepositsInTheChosenCurrencyOnly() {
        // Ada's USD 5 outranks Ben, who has none in USD and counts as zero;
        // nothing is added across currencies.
        AgentPerformance table = build(AgentSortMetric.BOOKED_INITIAL_DEPOSITS, "USD");

        assertThat(table.rows()).extracting(AgentRow::agentId).first().isEqualTo("sub-ada");
        assertThat(table.sortCurrency()).isEqualTo("USD");
        assertThat(row(table, "sub-ben").bookedInitialDeposits()).doesNotContainKey("USD");
    }

    @Test
    void anAgentWhoOpenedNoAccountsHasNoDeposits() {
        assertThat(row(build(AgentSortMetric.CLIENTS_ONBOARDED, "SGD"), "sub-cai").bookedInitialDeposits()).isEmpty();
    }

    private static AgentPerformance build(AgentSortMetric sort, String currency) {
        return AgentPerformanceTable.build(ROSTER, CLIENTS, ACCOUNTS, sort, currency);
    }

    private static AgentRow row(AgentPerformance table, String agentId) {
        return table.rows().stream().filter(row -> row.agentId().equals(agentId)).findFirst().orElseThrow();
    }

    private static AgentSummary agent(String sub, String firstName, boolean enabled) {
        return new AgentSummary("id-" + sub, sub, firstName, "Lim", firstName.toLowerCase() + "@x.com", "AGENT", enabled);
    }
}
