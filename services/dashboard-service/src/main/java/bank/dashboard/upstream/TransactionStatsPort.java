package bank.dashboard.upstream;

import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.upstream.dto.TransactionStats;

/**
 * Transaction figures, served by transaction-service, the SFTP receiver's
 * read API (ADR-0064).
 *
 * <p>{@link #stats} takes no account filter on purpose. For an agent,
 * user-service asks account-service which accounts the caller owns, so there
 * is no parameter an agent could supply to widen their view.
 */
public interface TransactionStatsPort {

    TransactionStats stats(DashboardWindow window);
}
