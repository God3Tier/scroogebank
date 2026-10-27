package transaction

import (
	"crypto/sha256"
	"encoding/hex"
	"math/rand"
	"time"

	"producer/src/accounts"
)

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
}

// GenerateHistory returns deterministic matched-transaction fixtures for the
// six complete calendar months before anchor's current UTC month. IDs and
// source fingerprints remain stable when the same account IDs are supplied.
func GenerateHistory(accountIDs []string, anchor time.Time) []Transaction {
	if len(accountIDs) == 0 {
		return nil
	}
	end := time.Date(anchor.UTC().Year(), anchor.UTC().Month(), 1, 0, 0, 0, 0, time.UTC)
	start := end.AddDate(0, -6, 0)
	rows := make([]Transaction, 0, len(accountIDs)*36)
	for _, accountID := range accountIDs {
		for month := 0; month < 6; month++ {
			at := start.AddDate(0, month, 0).Add(5 * time.Hour)
			if !at.Before(end) {
				continue
			}
			fingerprint := "synthetic-employer-" + stableID(accountID, "employer")[10:22]
			transactionDate := at.Format(time.RFC3339)
			rows = append(rows, Transaction{
				TransactionID: stableID(accountID, "recurring-credit", transactionDate),
				AccountID:     accountID, TransactionType: "Deposit", Amount: 4200,
				Currency: currency, TransferDate: at, Status: "Completed",
				Description: "SYNTHETIC RECURRING CREDIT", ActivityKind: "Automated",
				SourceFingerprint: fingerprint,
			})
		}
		for week := 0; week < 24; week++ {
			at := start.AddDate(0, 0, 2+week*7).Add(time.Duration((week%10)+8) * time.Hour)
			if !at.Before(end) {
				continue
			}
			rows = append(rows, Transaction{
				TransactionID: stableID(accountID, "customer-activity", at.Format(time.RFC3339)),
				AccountID:     accountID, TransactionType: "Withdrawal", Amount: float64(18 + (week*13)%240),
				Currency: currency, TransferDate: at, Status: "Completed",
				Description: "SYNTHETIC CUSTOMER TRANSACTION", ActivityKind: "CustomerInitiated",
			})
		}
	}
	return rows
}

func stableID(parts ...string) string {
	input := "client-health-history-v1"
	for _, part := range parts {
		input += "\x00" + part
	}
	digest := sha256.Sum256([]byte(input))
	return "synthetic-" + hex.EncodeToString(digest[:16])
}

func GenerateRandomSet(list ...accounts.Account) []Transaction {
	roll := rand.Intn(10)
	roll += 1
	transactionSet := make([]Transaction, 0, roll)

	for _ = range roll {
		weight := rand.Float64()

		baseTransaction := generateTransaction(list)

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
