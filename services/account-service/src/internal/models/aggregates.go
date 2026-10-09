package models

import "time"

// Window is a reporting period of whole days, both ends included (ADR-0038).
type Window struct {
	From time.Time
	To   time.Time
}

// DailyValue is one day of accounts opened, with their opening deposits.
type DailyValue struct {
	Date  string `json:"date" boil:"day"`
	Count int64  `json:"count" boil:"count"`
	Value string `json:"value" boil:"value"`
}

// AccountStats are the account figures the business dashboard shows. Deleted
// accounts are never counted (ADR-0036). Amounts are decimal strings, so no
// precision is lost on the way.
type AccountStats struct {
	// Total and the breakdowns cover every account in scope.
	Total      int64            `json:"total"`
	ByType     map[string]int64 `json:"byType"`
	ByStatus   map[string]int64 `json:"byStatus"`
	ByCurrency map[string]int64 `json:"byCurrency"`
	ByBranch   map[string]int64 `json:"byBranch"`
	// The rest covers accounts opened in the window. Deposits are summed only
	// when those accounts share one currency, which Currency names; it is
	// empty when none were opened.
	OpenedInWindow        int64        `json:"openedInWindow"`
	BookedInitialDeposits string       `json:"bookedInitialDeposits"`
	Currency              string       `json:"currency,omitempty"`
	DailyOpened           []DailyValue `json:"dailyOpened"`
	// ByAgent is filled only for an admin who asks for it.
	ByAgent []AgentAccountCounts `json:"byAgent,omitempty"`
}

// AgentAccountCounts are one agent's accounts opened in the window, keyed by
// the owner's Cognito sub.
type AgentAccountCounts struct {
	AgentID               string `json:"agentId" boil:"agent_id"`
	AccountsOpened        int64  `json:"accountsOpened" boil:"accounts_opened"`
	BookedInitialDeposits string `json:"bookedInitialDeposits" boil:"booked_initial_deposits"`
}

// AccountIDs are the accounts the caller owns.
type AccountIDs struct {
	AccountIDs []string `json:"accountIds"`
}
