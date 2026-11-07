package bank.dashboard.service;

import bank.dashboard.dto.AgentSortMetric;
import bank.dashboard.dto.DashboardResponse;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.dto.Panel;
import bank.dashboard.dto.PanelReason;
import bank.dashboard.dto.Scope;
import bank.dashboard.exception.UpstreamUnavailableException;
import bank.dashboard.security.CurrentUser;
import bank.dashboard.upstream.AccountStatsPort;
import bank.dashboard.upstream.ClientStatsPort;
import bank.dashboard.upstream.TransactionStatsPort;
import bank.dashboard.upstream.UserRosterPort;
import bank.dashboard.upstream.dto.AccountStats;
import bank.dashboard.upstream.dto.AgentSummary;
import bank.dashboard.upstream.dto.ClientStats;
import bank.dashboard.upstream.dto.TeamComparison;
import bank.dashboard.window.WindowParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Builds each endpoint's response by deciding which panels belong to it, then
 * handing them to {@link PanelAssembler} to resolve independently.
 *
 * <p>Scope comes from the token and nowhere else. No method here accepts an
 * agent identifier, and none is passed upstream: the service that owns the
 * data decides what the caller may see (ADR-0033). An agent therefore has no
 * parameter to forge, which is a stronger guarantee than checking one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    // The per-agent table's inputs, named so the assembler's log lines say
    // which of them failed.
    private static final String AGENTS_ROSTER = "agents.roster";
    private static final String AGENTS_CLIENTS = "agents.clients";
    private static final String AGENTS_ACCOUNTS = "agents.accounts";

    private final ClientStatsPort clientStats;
    private final AccountStatsPort accountStats;
    private final TransactionStatsPort transactionStats;
    private final UserRosterPort userRoster;
    private final PanelAssembler assembler;
    private final DashboardCache cache;
    private final CurrentUser currentUser;
    private final Clock clock;

    public ServedResponse overview(DashboardWindow window) {
        return cached("overview", window, null, () -> {
            Map<String, Supplier<Object>> panels = new LinkedHashMap<>();
            panels.put("clients", () -> clientStats.stats(window, false));
            panels.put("accounts", () -> accountStats.stats(window, false));
            panels.put("transactions", () -> transactionStats.stats(window));
            if (currentUser.isAdmin()) {
                // Only admins see the roster, and here only as counts.
                panels.put("agents", () -> AgentCount.of(userRoster.agents()));
            } else {
                // An agent sees how they compare, never whom against.
                panels.put("teamComparison", () -> teamComparison(window));
            }
            return build(window, panels);
        });
    }

    /**
     * Only for the last 7, 30 or 90 days ending today: client-service refuses
     * other windows, because medians over many chosen windows could be set
     * against each other to narrow down individual figures (ADR-0038).
     */
    private TeamComparison teamComparison(DashboardWindow window) {
        LocalDate today = LocalDate.now(clock.withZone(WindowParser.BUSINESS_ZONE));
        int days = (int) window.days();
        if (!window.to().equals(today) || !TeamComparison.WINDOW_DAYS.contains(days)) {
            // Not an upstream failure, but it takes the same path: one
            // unavailable panel with a reason the UI can explain.
            throw new UpstreamUnavailableException(PanelReason.UNSUPPORTED_WINDOW,
                    "the team comparison covers only the last 7, 30 or 90 days");
        }
        return clientStats.teamComparison(days).withoutSmallCohortMedian();
    }

    public ServedResponse portfolio(DashboardWindow window) {
        return cached("portfolio", window, null, () -> {
            Map<String, Supplier<Object>> panels = new LinkedHashMap<>();
            panels.put("clients", () -> clientStats.stats(window, false));
            panels.put("accounts", () -> accountStats.stats(window, false));
            DashboardResponse response = build(window, panels);
            response.panels().put("accountsPerClient",
                    accountsPerClient(response.panels().get("clients"), response.panels().get("accounts")));
            return response;
        });
    }

    public ServedResponse transactions(DashboardWindow window) {
        return cached("transactions", window, null, () -> {
            Map<String, Supplier<Object>> panels = new LinkedHashMap<>();
            panels.put("transactions", () -> transactionStats.stats(window));
            return build(window, panels);
        });
    }

    public ServedResponse actionItems(DashboardWindow window, int staleDays) {
        return cached("action-items", window, String.valueOf(staleDays), () -> {
            Map<String, Supplier<Object>> panels = new LinkedHashMap<>();
            panels.put("clients", () -> clientStats.actionItems(staleDays));
            panels.put("transactions", () -> transactionStats.stats(window));
            return build(window, panels);
        });
    }

    /**
     * Per-agent performance. The controller enforces admin-only access before
     * this is reached; client-service and account-service are asked for
     * agent-grouped figures, which they also refuse to an agent caller.
     */
    public ServedResponse agentPerformance(DashboardWindow window, AgentSortMetric sort, String currency) {
        return cached("agents", window, sort.wireValue() + ":" + currency, () -> {
            // The table needs three upstreams. They are fetched in parallel
            // like any other panels and joined here: one after another, they
            // shared a single call's budget, which three cold calls after a
            // restart could exceed. Only the joined panel is returned.
            Map<String, Supplier<Object>> inputs = new LinkedHashMap<>();
            inputs.put(AGENTS_ROSTER, userRoster::agents);
            inputs.put(AGENTS_CLIENTS, () -> clientStats.stats(window, true));
            inputs.put(AGENTS_ACCOUNTS, () -> accountStats.stats(window, true));
            Map<String, Panel> panels = new LinkedHashMap<>();
            panels.put("agents", agentsTable(assembler.assemble(inputs), sort, currency));
            return response(window, panels);
        });
    }

    /** How many agents are on the roster, and how many of them are enabled. */
    public record AgentCount(long total, long active) {

        static AgentCount of(List<AgentSummary> agents) {
            return new AgentCount(agents.size(), agents.stream().filter(AgentSummary::enabled).count());
        }
    }

    /**
     * Accounts per client: the dashboard divides the two totals it already has,
     * rather than joining clients to accounts across services (ADR-0038).
     *
     * @param ratio null when the caller has no clients
     */
    public record AccountsPerClient(Double ratio) {
    }

    private static Panel accountsPerClient(Panel clients, Panel accounts) {
        if (clients.isUnavailable()) {
            return Panel.unavailable(clients.reason());
        }
        if (accounts.isUnavailable()) {
            return Panel.unavailable(accounts.reason());
        }
        long clientTotal = ((ClientStats) clients.data()).total();
        long accountTotal = ((AccountStats) accounts.data()).total();
        return Panel.ok(new AccountsPerClient(clientTotal == 0 ? null : (double) accountTotal / clientTotal));
    }

    /**
     * Joins the per-agent table's inputs. The first unavailable input decides
     * the reason, as for {@link #accountsPerClient}.
     */
    @SuppressWarnings("unchecked")
    private static Panel agentsTable(Map<String, Panel> inputs, AgentSortMetric sort, String currency) {
        for (Panel input : inputs.values()) {
            if (input.isUnavailable()) {
                return Panel.unavailable(input.reason());
            }
        }
        try {
            return Panel.ok(AgentPerformanceTable.build(
                    (List<AgentSummary>) inputs.get(AGENTS_ROSTER).data(),
                    (ClientStats) inputs.get(AGENTS_CLIENTS).data(),
                    (AccountStats) inputs.get(AGENTS_ACCOUNTS).data(),
                    sort,
                    currency));
        } catch (RuntimeException ex) {
            // An answer the join cannot use, such as one missing its rows,
            // costs the panel as it did when the join ran inside the call.
            log.warn("Panel 'agents' failed", ex);
            return Panel.unavailable(PanelReason.UPSTREAM_ERROR);
        }
    }

    private DashboardResponse build(DashboardWindow window, Map<String, Supplier<Object>> panels) {
        return response(window, assembler.assemble(panels));
    }

    private DashboardResponse response(DashboardWindow window, Map<String, Panel> panels) {
        return new DashboardResponse(
                Instant.now(clock),
                window,
                currentUser.isAdmin() ? Scope.ADMIN : Scope.AGENT,
                panels);
    }

    private ServedResponse cached(String endpoint, DashboardWindow window, String variant,
                                  Supplier<DashboardResponse> compute) {
        DashboardCache.Key key = new DashboardCache.Key(
                currentUser.subject(), currentUser.isAdmin(), endpoint, window, variant);
        return cache.get(key, compute);
    }
}
