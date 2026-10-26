package models

import "time"

// ClientHealthTransactionQuery selects matched activity for one client.
type ClientHealthTransactionQuery struct {
	ClientID string
	From     time.Time // inclusive
	To       time.Time // exclusive
	After    *TransactionCursor
	Limit    int
}

// TransactionListQuery selects one page of the admin transaction list
// (ADR-0043): at most Limit rows, older than After when it is set, and only
// those with Status when it is not empty.
type TransactionListQuery struct {
	After  *TransactionCursor
	Status string
	Limit  int
}

// TransactionCursor is the last row of the previous page. Rows are ordered by
// date, then ID, both descending, so the pair names a position exactly.
type TransactionCursor struct {
	Date time.Time `json:"date"`
	ID   string    `json:"id"`
}

// ClientHealthTransaction is the restricted projection returned to the
// Client Health Monitor. It contains no source reference or import metadata.
type ClientHealthTransaction struct {
	TransactionID     string    `boil:"transaction_id"`
	ClientID          string    `boil:"client_id"`
	AccountID         string    `boil:"account_id"`
	TransactionType   string    `boil:"transaction_type"`
	Amount            string    `boil:"amount"`
	Currency          string    `boil:"currency"`
	TransactionDate   time.Time `boil:"transaction_date"`
	Status            string    `boil:"status"`
	ActivityKind      string    `boil:"activity_kind"`
	SourceFingerprint *string   `boil:"source_fingerprint"`
}
