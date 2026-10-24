package poller

import (
	"fmt"
	"math"
	"os"
	"strings"

	"receiver/src/internal/models"
)

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func normalizeTransaction(transaction *models.Transaction) error {
	transaction.AccountID = strings.TrimSpace(transaction.AccountID)
	if strings.TrimSpace(transaction.TransactionID) == "" {
		return fmt.Errorf("transaction ID is required")
	}
	if strings.TrimSpace(transaction.AccountID) == "" {
		return fmt.Errorf("account ID is required")
	}
	if transaction.TransferDate.IsZero() {
		return fmt.Errorf("transaction date is required")
	}
	if transaction.Amount < 0 || math.IsNaN(transaction.Amount) || math.IsInf(transaction.Amount, 0) {
		return fmt.Errorf("amount must be a finite non-negative number")
	}
	// Account currencies are upper case, and a Matched transaction's must
	// equal its account's (ADR-0037).
	transaction.Currency = strings.ToUpper(transaction.Currency)
	if transaction.Currency != "" && len(transaction.Currency) != 3 {
		return fmt.Errorf("currency must contain a three-character code")
	}

	switch strings.ToUpper(transaction.TransactionType) {
	case "DEBIT":
		transaction.TransactionType = "Withdrawal"
	case "CREDIT", "DEPOSIT":
		transaction.TransactionType = "Deposit"
	case "WITHDRAWAL":
		transaction.TransactionType = "Withdrawal"
	default:
		return fmt.Errorf("unsupported transaction type %q", transaction.TransactionType)
	}

	switch strings.ToUpper(transaction.Status) {
	case "COMPLETED":
		transaction.Status = "Completed"
	case "PENDING":
		transaction.Status = "Pending"
	case "FAILED":
		transaction.Status = "Failed"
	default:
		return fmt.Errorf("unsupported transaction status %q", transaction.Status)
	}
	return nil
}
