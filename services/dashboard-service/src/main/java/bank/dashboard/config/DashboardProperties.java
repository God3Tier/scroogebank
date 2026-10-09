package bank.dashboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables from {@code application.yml}. Deliberately not environment
 * variables: these are behavioural choices recorded in ADRs, not per-deployment
 * configuration, so changing one should be a reviewed commit rather than a
 * task-definition edit.
 *
 * @param window   reporting window limits
 * @param cache    per-task cache settings (ADR-0033)
 * @param upstream fan-out budgets
 */
@ConfigurationProperties(prefix = "dashboard")
public record DashboardProperties(Window window, Cache cache, Upstream upstream) {

    /**
     * @param defaultDays window length when the caller gives no dates
     * @param maxDays     guard against one request asking an upstream to
     *                    aggregate an unbounded range
     */
    public record Window(int defaultDays, int maxDays) {
    }

    /**
     * @param ttlSeconds how long a computed response stays usable
     * @param maxEntries bound on entries held, a starting value rather than a
     *                   measured one
     */
    public record Cache(long ttlSeconds, long maxEntries) {
    }

    /**
     * Each base URL is where that service is reachable, or blank when it is
     * not wired up. Blank is a supported state, not a misconfiguration: its
     * panels then report UNAVAILABLE like any panel whose upstream is absent.
     *
     * @param perCallTimeoutMs      budget for a single upstream call
     * @param overallBudgetMs       budget for the whole fan-out, so a request
     *                              cannot take the sum of its slowest upstreams
     * @param userServiceBaseUrl    the agent roster and transaction figures
     * @param clientServiceBaseUrl  client figures and the work queue
     * @param accountServiceBaseUrl account figures
     */
    public record Upstream(long perCallTimeoutMs, long overallBudgetMs, String userServiceBaseUrl,
                           String clientServiceBaseUrl, String accountServiceBaseUrl) {
    }
}
