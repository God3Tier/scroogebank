package bank.dashboard.dto;

import java.time.Instant;
import java.util.Map;

/**
 * The response every dashboard endpoint returns.
 *
 * @param generatedAt when these figures were computed, which is not when the
 *                    request was served: on a cache hit it is up to the cache
 *                    TTL earlier. Exposed rather than hidden because caching
 *                    without publishing this is how dashboards lose people's
 *                    trust -- someone makes a change, refreshes, sees the old
 *                    number and concludes the system is broken.
 * @param window      the reporting window the figures cover
 * @param scope       how the figures were scoped, derived from the token
 * @param panels      keyed by panel name; which keys appear depends on the
 *                    endpoint and the caller's role
 */
public record DashboardResponse(
        Instant generatedAt,
        DashboardWindow window,
        Scope scope,
        Map<String, Panel> panels
) {

    /** True when nothing at all resolved, which is the only case that is an error. */
    public boolean allPanelsUnavailable() {
        return !panels.isEmpty() && panels.values().stream().allMatch(Panel::isUnavailable);
    }

    /** True when any panel failed in a way the next request may not repeat. */
    public boolean hasTransientFailure() {
        return panels.values().stream().anyMatch(panel -> panel.reason() != null && panel.reason().isTransient());
    }
}
