package models

// Account is what account-service says about a CRM account at import: its
// client, and its currency, which each of its transactions must be in
// (ADR-0037).
type Account struct {
	ClientID string
	Currency string
}
