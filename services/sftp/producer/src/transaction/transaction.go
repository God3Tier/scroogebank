package transaction

import (
	"math/rand"
	"time"
)

// type Transaction struct {
// 	TransactionID   string
// 	AccountNo       string
// 	TransferDate    time.Time
// 	TransactionType string
// 	Amount          float64
// 
// 	Currency        string
// 	Description     string
// 	Status          string
// }
type Transaction struct {
    TransactionID   string
    AccountID       string 
    TransactionType string 
    Amount          float64
    Currency        string
    TransferDate    time.Time
    Status          string
    Description     string
}

func GenerateRandomSet() []Transaction {
	roll := rand.Intn(10)
	roll += 1
	transactionSet := make([]Transaction, 0, roll)

	for _ = range roll {
		weight := rand.Float64()

		baseTransaction := generateTransaction()

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
