// Package fault builds report files with faults in them, so the demo can
// show how the import rejects and audits each one (ADR-0024 decision 1).
package fault

import (
	"bytes"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"math/rand/v2"
	"strconv"
	"time"

	"producer/src/accounts"
	"producer/src/transaction"
)

// Kinds lists the faults, in the order the README describes them.
var Kinds = []string{"corrupt", "partial", "all-invalid", "unsupported", "resend"}

// File is one file to publish.
type File struct {
	Extension string // ".json" or ".csv"
	Content   []byte
}

// validRows is how many valid transactions a fault file holds.
const validRows = 3

// Build returns the files for one kind of fault, in the order to publish
// them.
func Build(kind string, list []accounts.Account) ([]File, error) {
	switch kind {
	case "corrupt":
		// Any proper prefix of a JSON array is incomplete, so the file
		// cannot be parsed: a truncated upload.
		content, err := json.Marshal(valid(list, validRows))
		if err != nil {
			return nil, err
		}
		return []File{{Extension: ".json", Content: content[:1+rand.IntN(len(content)-1)]}}, nil
	case "partial":
		return jsonFile(append(valid(list, validRows), invalid(list)...))
	case "all-invalid":
		return jsonFile(invalid(list))
	case "unsupported":
		content, err := csvFile(valid(list, validRows))
		if err != nil {
			return nil, err
		}
		return []File{{Extension: ".csv", Content: content}}, nil
	case "resend":
		// The same transaction twice: Pending, then Completed with another
		// amount. The import moves the status forward and keeps the first
		// amount (ADR-0024 decision 4).
		first := transaction.Generate(list)
		first.Status = "PENDING"
		second := first
		second.Status = "COMPLETED"
		second.Amount = first.Amount + 10
		firstFile, err := jsonFile([]transaction.Transaction{first})
		if err != nil {
			return nil, err
		}
		secondFile, err := jsonFile([]transaction.Transaction{second})
		if err != nil {
			return nil, err
		}
		return append(firstFile, secondFile...), nil
	default:
		return nil, fmt.Errorf("unknown fault %q: use one of %v", kind, Kinds)
	}
}

// invalid returns one transaction for each reason the import rejects a row.
func invalid(list []accounts.Account) []transaction.Transaction {
	rows := valid(list, 5)
	rows[0].TransactionID = ""
	rows[1].Amount = -26
	rows[2].Status = "SETTLED"
	rows[3].TransactionType = "REFUND"
	rows[4].Currency = "SGDX"
	return rows
}

func valid(list []accounts.Account, n int) []transaction.Transaction {
	rows := make([]transaction.Transaction, n)
	for i := range rows {
		rows[i] = transaction.Generate(list)
	}
	return rows
}

func jsonFile(rows []transaction.Transaction) ([]File, error) {
	content, err := json.Marshal(rows)
	if err != nil {
		return nil, err
	}
	return []File{{Extension: ".json", Content: content}}, nil
}

func csvFile(rows []transaction.Transaction) ([]byte, error) {
	var buffer bytes.Buffer
	writer := csv.NewWriter(&buffer)
	records := [][]string{{"transaction_id", "account_id", "transaction_type", "amount", "currency", "transfer_date", "status", "description"}}
	for _, row := range rows {
		records = append(records, []string{
			row.TransactionID, row.AccountID, row.TransactionType, strconv.FormatFloat(row.Amount, 'f', 2, 64),
			row.Currency, row.TransferDate.Format(time.RFC3339), row.Status, row.Description,
		})
	}
	if err := writer.WriteAll(records); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}
