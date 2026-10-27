package models

import "time"

// Window is a reporting period of whole days, both ends included (ADR-0038).
type Window struct {
	From time.Time
	To   time.Time
}

// DailyValue is one day of accounts opened, with their opening deposits.
// Value is left out when the window's accounts span more than one currency.
type DailyValue struct {
	Date  string  `json:"date"`
	Count int64   `json:"count"`
	Value *string `json:"value,omitempty"`
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
	// The rest covers accounts opened in the window. Deposits are summed per
	// currency, never across currencies (ADR-0062); Currencies lists them,
	// sorted, and is empty when none were opened.
	OpenedInWindow        int64             `json:"openedInWindow"`
	BookedInitialDeposits map[string]string `json:"bookedInitialDeposits"`
	Currencies            []string          `json:"currencies"`
	DailyOpened           []DailyValue      `json:"dailyOpened"`
	// ByAgent is filled only for an admin who asks for it.
	ByAgent []AgentAccountCounts `json:"byAgent,omitempty"`
}

// AgentAccountCounts are one agent's accounts opened in the window, keyed by
// the owner's Cognito sub, with their deposits per currency.
type AgentAccountCounts struct {
	AgentID               string            `json:"agentId"`
	AccountsOpened        int64             `json:"accountsOpened"`
	BookedInitialDeposits map[string]string `json:"bookedInitialDeposits"`
}

// AccountIDs are the accounts the caller owns.
type AccountIDs struct {
	AccountIDs []string `json:"accountIds"`
}
