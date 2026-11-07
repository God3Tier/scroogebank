package models

import "time"

// TransactionStatsQuery selects the transactions a total covers: every
// transaction when All is set, otherwise those linked to AccountIDs.
// transaction-service sets it from the verified caller, never from request
// data (ADR-0064).
type TransactionStatsQuery struct {
	From       time.Time
	To         time.Time
	AccountIDs []string
	All        bool
}

// TransactionStats are the transaction figures the business dashboard shows.
// Amounts are decimal strings, so no precision is lost on the way.
type TransactionStats struct {
	// Total, ByStatus, ByType, Currencies, TotalValue and Daily cover the
	// window, in Singapore days.
	Total      int64            `json:"total"`
	ByStatus   map[string]int64 `json:"byStatus"`
	ByType     map[string]int64 `json:"byType"`
	Currencies []string         `json:"currencies"`
	// TotalValue and each day's value are summed only when the window's
	// transactions share one currency; otherwise they are left out (ADR-0038).
	TotalValue string              `json:"totalValue,omitempty"`
	Daily      []DailyTransactions `json:"daily"`
	// PendingBacklog counts transactions still Pending a day after their
	// date, whenever that was.
	PendingBacklog int64 `json:"pendingBacklog"`
	// Unmatched counts the window's transactions linked to no account. Only
	// an every-transaction total includes it.
	Unmatched *int64 `json:"unmatched,omitempty"`
}

// DailyTransactions is one Singapore day of transactions.
type DailyTransactions struct {
	Date  string `json:"date" boil:"day"`
	Count int64  `json:"count" boil:"count"`
	Value string `json:"value,omitempty" boil:"value"`
}
