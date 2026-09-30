package transaction

import (
	"math/rand"
	"time"
)

//	type Transaction struct {
//		TransactionID   string
//		AccountNo       string
//		TransferDate    time.Time
//		TransactionType string
//		Amount          float64
//
//		Currency        string
//		Description     string
//		Status          string
//	}
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

func GenerateRandomSet(accountIDs ...string) []Transaction {
	roll := rand.Intn(10)
	roll += 1
	transactionSet := make([]Transaction, 0, roll)

	for _ = range roll {
		weight := rand.Float64()

		baseTransaction := generateTransaction(accountIDs)

		// TODO: Add very very small chance of failure
		switch {
		case weight < 0.001:
			baseTransaction.Amount = -26.0
		case weight < 0.005:
			baseTransaction.Status = "Invalid status"
		case weight < 0.010:
			baseTransaction.Currency = "Unsupported"
		}

		transactionSet = append(transactionSet, baseTransaction)
	}

	return transactionSet
}
