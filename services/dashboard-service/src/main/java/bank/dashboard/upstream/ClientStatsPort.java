package bank.dashboard.upstream;

import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.upstream.dto.ClientActionItems;
import bank.dashboard.upstream.dto.ClientStats;
import bank.dashboard.upstream.dto.TeamComparison;

/**
 * Client figures, owned by Feature 2's client-profile-management-service.
 *
 * <p>No method takes an agent identifier. Scope is derived by the owning
 * service from the caller's forwarded token, so an agent has nothing to forge
 * and the ownership rule is not reimplemented here (ADR-0033).
 */
public interface ClientStatsPort {

    ClientStats stats(DashboardWindow window, boolean groupByAgent);

    ClientActionItems actionItems(int staleDays);

    /**
     * The caller's clients onboarded over the last {@code days} (7, 30 or 90)
     * against the team median, computed by client-service so no other
     * agent's figure reaches the dashboard.
     */
    TeamComparison teamComparison(int days);
}
