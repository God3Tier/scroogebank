package bank.dashboard.upstream.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Account figures from account-service's {@code GET /aggregates/accounts/stats},
 * scoped by the caller (ADR-0038).
 *
 * <p>{@code bookedInitialDeposits} is the opening deposit of each account
 * opened in the window, never a balance: no balance exists anywhere in the
 * system. account-service sums it only when those accounts share one
 * currency, named in {@code currency}; otherwise it refuses, and the panel
 * reports {@code mixed_currencies}.
 *
 * @param total          every account in scope, whatever its opening date;
 *                       the four breakdowns cover the same accounts
 * @param openedInWindow accounts opened in the window
 * @param currency       their currency, or null when none were opened
 * @param dailyOpened    per day, with that day's deposits as {@code value}
 * @param byAgent        per-agent figures for the window; only for an admin
 *                       who asks for them
 */
public record AccountStats(
        long total,
        Map<String, Long> byType,
        Map<String, Long> byStatus,
        Map<String, Long> byCurrency,
        Map<String, Long> byBranch,
        long openedInWindow,
        BigDecimal bookedInitialDeposits,
        String currency,
        List<DailyCount> dailyOpened,
        List<AgentAccountCounts> byAgent
) {

    /** One agent's accounts opened in the window, keyed by Cognito sub. */
    public record AgentAccountCounts(String agentId, long accountsOpened, BigDecimal bookedInitialDeposits) {
    }
}
