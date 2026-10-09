package bank.dashboard.upstream.dto;

import java.util.List;
import java.util.Map;

/**
 * Client figures from client-service's {@code GET /aggregates/clients/stats},
 * scoped by the caller (ADR-0038).
 *
 * @param total                every client in scope, whenever created
 * @param byVerificationStatus the same clients by status, upper-cased
 * @param createdInWindow      clients created in the window
 * @param dailyCreated         the same, per Singapore day
 * @param byAgent              per-agent counts of clients created in the
 *                             window; only for an admin who asks for them
 */
public record ClientStats(
        long total,
        Map<String, Long> byVerificationStatus,
        long createdInWindow,
        List<DailyCount> dailyCreated,
        List<AgentClientCounts> byAgent
) {

    /** One agent's clients created in the window, keyed by Cognito sub. */
    public record AgentClientCounts(
            String agentId,
            long clientsOnboarded,
            long verified,
            long unverified
    ) {
    }
}
