package bank.dashboard.upstream;

import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.upstream.dto.TransactionStats;

/**
 * Transaction figures, served by user-service in front of the SFTP receiver
 * (ADR-0039).
 *
 * <p>{@link #stats} takes no account filter on purpose. For an agent,
 * user-service asks account-service which accounts the caller owns, so there
 * is no parameter an agent could supply to widen their view.
 */
public interface TransactionStatsPort {

    TransactionStats stats(DashboardWindow window);
}
