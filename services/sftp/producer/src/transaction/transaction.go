package transaction

import (
	"crypto/sha256"
	"encoding/hex"
	"math/rand"
	"sort"
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

// GenerateHistory returns deterministic matched-transaction cases for the six
// complete calendar months before anchor's current UTC month. Accounts are
// sorted by ID; the first four represent a recurring-credit interruption,
// activity decline, sustained net outflow, and a healthy control, respectively.
func GenerateHistory(accountsList []accounts.Account, anchor time.Time) []Transaction {
	if len(accountsList) < 4 {
		return nil
	}
	accountsList = append([]accounts.Account(nil), accountsList...)
	sort.Slice(accountsList, func(i, j int) bool { return accountsList[i].ID < accountsList[j].ID })
	end := time.Date(anchor.UTC().Year(), anchor.UTC().Month(), 1, 0, 0, 0, 0, time.UTC)
	start := end.AddDate(0, -6, 0)
	rows := make([]Transaction, 0, len(accountsList)*64)
	for accountIndex, account := range accountsList {
		scenario := accountIndex % 4
		for month := 0; month < 6; month++ {
			if scenario == 0 && month >= 4 {
				continue
			}
			at := start.AddDate(0, month, 0).Add(5 * time.Hour)
			if !at.Before(end) {
				continue
			}
			fingerprint := "synthetic-employer-" + stableID(account.ID, "employer")[10:22]
			rows = append(rows, Transaction{
				TransactionID: stableID(account.ID, "recurring-credit", at.Format(time.RFC3339)),
				AccountID:     account.ID, TransactionType: "Deposit", Amount: 4200,
				Currency: account.Currency, TransferDate: at, Status: "Completed",
				Description: "SYNTHETIC RECURRING CREDIT", ActivityKind: "Automated",
				SourceFingerprint: fingerprint,
			})
		}
		for week := 0; week < 26; week++ {
			count := 2
			if scenario == 1 {
				count = 3
				if week >= 16 {
					count = 1
				}
			}
			for activity := 0; activity < count; activity++ {
				at := start.AddDate(0, 0, 2+week*7).Add(time.Duration(8+activity*3) * time.Hour)
				if !at.Before(end) {
					continue
				}
				amount := float64(120 + (week+activity)%40)
				if scenario == 2 {
					amount = 200
					if week >= 16 {
						amount = 1400
					}
				}
				rows = append(rows, Transaction{
					TransactionID: stableID(account.ID, "customer-activity", at.Format(time.RFC3339)),
					AccountID:     account.ID, TransactionType: "Withdrawal", Amount: amount,
					Currency: account.Currency, TransferDate: at, Status: "Completed",
					Description: "SYNTHETIC CUSTOMER TRANSACTION", ActivityKind: "CustomerInitiated",
				})
			}
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
