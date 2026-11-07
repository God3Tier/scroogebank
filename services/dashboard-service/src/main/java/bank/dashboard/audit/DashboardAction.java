package bank.dashboard.audit;

/**
 * The audited actions. One per dashboard endpoint, so the audit trail records
 * what was looked at and not merely that the service was called.
 */
public enum DashboardAction {

    VIEW_OVERVIEW,
    VIEW_PORTFOLIO,
    VIEW_TRANSACTIONS,
    VIEW_ACTION_ITEMS,
    VIEW_AGENT_PERFORMANCE
}
