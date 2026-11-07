package models

import "time"

type Transaction struct {
	TransactionID     string    `json:"transaction_id"`
	AccountID         string    `json:"account_id"`
	TransactionType   string    `json:"transaction_type"`
	Amount            float64   `json:"amount"`
	Currency          string    `json:"currency"`
	TransferDate      time.Time `json:"transfer_date"`
	Status            string    `json:"status"`
	Description       string    `json:"description"`
	ActivityKind      string    `json:"activity_kind"`
	SourceFingerprint string    `json:"source_fingerprint,omitempty"`

	// Set at import from account-service's answer, never from the file
	// (ADR-0037). Empty when the reference is not a CRM account.
	LinkedAccountID string `json:"-"`
	ClientID        string `json:"-"`
	ImportStatus    string `json:"-"`
}
