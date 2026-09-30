package models

import "time"

type Transaction struct {
	TransactionID   string    `json:"transaction_id"`
	AccountID       string    `json:"account_id"`
	TransactionType string    `json:"transaction_type"`
	Amount          float64   `json:"amount"`
	Currency        string    `json:"currency"`
	TransferDate    time.Time `json:"transfer_date"`
	Status          string    `json:"status"`
	Description     string    `json:"description"`
}
