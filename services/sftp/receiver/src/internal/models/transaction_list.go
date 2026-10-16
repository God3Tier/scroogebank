package models

import "time"

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
	Date time.Time
	ID   string
}
