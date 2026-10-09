package bank.dashboard.service;

import bank.dashboard.dto.AgentSortMetric;
import bank.dashboard.upstream.dto.AccountStats;
import bank.dashboard.upstream.dto.AgentSummary;
import bank.dashboard.upstream.dto.ClientStats;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The admin's per-agent table. Names come from user-service and figures from
 * client-service and account-service; none knows about the others, so the
 * join by Cognito sub happens here and nowhere else.
 *
 * <p>Ranked by one explicit metric, never a composite score (ADR-0038).
 */
@UtilityClass
public class AgentPerformanceTable {

    /**
     * @param currency the deposits' currency, or null when no account was
     *                 opened in the window
     */
    public record AgentPerformance(List<AgentRow> rows, String sortedBy, String currency) {
    }

    /**
     * One agent's figures for the window.
     *
     * @param name             null when the sub is not an agent on the roster,
     *                         such as an admin who created a client
     * @param enabled          null on the same condition
     * @param verificationRate verified clients over clients onboarded, or null
     *                         when the agent onboarded none
     */
    public record AgentRow(
            String agentId,
            String name,
            Boolean enabled,
            long clientsOnboarded,
            long verified,
            long unverified,
            Double verificationRate,
            long accountsOpened,
            BigDecimal bookedInitialDeposits
    ) {
    }

    /**
     * Every enabled agent appears, with zeros if they did nothing in the
     * window, and so does anyone with figures. A disabled agent with no
     * figures is left out.
     */
    public AgentPerformance build(List<AgentSummary> roster, ClientStats clients, AccountStats accounts, AgentSortMetric sort) {
        Map<String, AgentSummary> agents = new LinkedHashMap<>();
        roster.stream().filter(agent -> agent.idpSub() != null).forEach(agent -> agents.put(agent.idpSub(), agent));
        Map<String, ClientStats.AgentClientCounts> clientRows = index(clients.byAgent(), ClientStats.AgentClientCounts::agentId);
        Map<String, AccountStats.AgentAccountCounts> accountRows = index(accounts.byAgent(), AccountStats.AgentAccountCounts::agentId);

        Set<String> ids = new LinkedHashSet<>();
        agents.values().stream().filter(AgentSummary::enabled).forEach(agent -> ids.add(agent.idpSub()));
        ids.addAll(clientRows.keySet());
        ids.addAll(accountRows.keySet());

        List<AgentRow> rows = ids.stream()
                .map(id -> row(id, agents.get(id), clientRows.get(id), accountRows.get(id)))
                .sorted(order(sort))
                .toList();
        return new AgentPerformance(rows, sort.wireValue(), accounts.currency());
    }

    private AgentRow row(String id, AgentSummary agent, ClientStats.AgentClientCounts clients,
                         AccountStats.AgentAccountCounts accounts) {
        long onboarded = clients == null ? 0 : clients.clientsOnboarded();
        long verified = clients == null ? 0 : clients.verified();
        return new AgentRow(
                id,
                agent == null ? null : agent.displayName(),
                agent == null ? null : agent.enabled(),
                onboarded,
                verified,
                clients == null ? 0 : clients.unverified(),
                onboarded == 0 ? null : (double) verified / onboarded,
                accounts == null ? 0 : accounts.accountsOpened(),
                accounts == null ? BigDecimal.ZERO : accounts.bookedInitialDeposits());
    }

    /** Highest first; agents without a figure last; then by name, then sub, so the order is stable. */
    private Comparator<AgentRow> order(AgentSortMetric sort) {
        Comparator<AgentRow> byMetric = switch (sort) {
            case CLIENTS_ONBOARDED -> Comparator.comparingLong(AgentRow::clientsOnboarded).reversed();
            case VERIFICATION_RATE -> Comparator.comparing(AgentRow::verificationRate,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case ACCOUNTS_OPENED -> Comparator.comparingLong(AgentRow::accountsOpened).reversed();
            case BOOKED_INITIAL_DEPOSITS -> Comparator.comparing(AgentRow::bookedInitialDeposits,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return byMetric
                .thenComparing(AgentRow::name, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AgentRow::agentId);
    }

    private <T> Map<String, T> index(List<T> rows, Function<T, String> agentId) {
        Map<String, T> indexed = new LinkedHashMap<>();
        if (rows != null) {
            rows.stream().filter(row -> Objects.nonNull(agentId.apply(row))).forEach(row -> indexed.put(agentId.apply(row), row));
        }
        return indexed;
    }
}
