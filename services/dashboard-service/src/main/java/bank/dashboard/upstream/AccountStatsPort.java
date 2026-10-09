package bank.dashboard.upstream;

import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.upstream.dto.AccountStats;

/**
 * Account figures, owned by account-service (ADR-0035).
 *
 * <p>No method takes an agent identifier. account-service scopes by the
 * caller's forwarded token: an agent sees the accounts they own.
 */
public interface AccountStatsPort {

    AccountStats stats(DashboardWindow window, boolean groupByAgent);
}
