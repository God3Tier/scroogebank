package bank.dashboard.upstream.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Transaction figures from transaction-service's
 * {@code GET /aggregates/transactions/stats}, for an admin's every
 * transaction or an agent's own accounts (ADR-0064).
 *
 * @param total          transactions in the window
 * @param currencies     the currencies among them
 * @param totalValue     their summed amount, or null when they span more than
 *                       one currency (ADR-0038)
 * @param daily          per Singapore day; each day's value is null on the
 *                       same condition
 * @param pendingBacklog transactions still Pending a day after their date,
 *                       whenever that was
 * @param unmatched      the window's transactions linked to no account; only
 *                       in an admin's figures
 */
public record TransactionStats(
        long total,
        Map<String, Long> byStatus,
        Map<String, Long> byType,
        List<String> currencies,
        BigDecimal totalValue,
        List<DailyCount> daily,
        long pendingBacklog,
        Long unmatched
) {
}
