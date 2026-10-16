package repository

import (
	"context"
	"fmt"
	"receiver/src/internal/models"

	"github.com/aarondl/null/v8"
	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/types"
	"github.com/ericlagergren/decimal"
)

func (i impl) SaveTransaction(ctx context.Context, transaction models.Transaction) error {
	return saveTransaction(ctx, i.db, transaction)
}

// saveTransaction stores one transaction through exec, which is the database
// or an import's transaction. A transaction already stored keeps its first
// version, except that a Pending status moves on to Completed or Failed: a
// later file can settle a transaction but never reopen it, and never changes
// its amount, date or account link (ADR-0024 decision 4, ADR-0037 decision 6).
// SQLBoiler's Upsert cannot express the conflict's WHERE, so this is plain SQL.
func saveTransaction(ctx context.Context, exec boil.ContextExecutor, transaction models.Transaction) error {
	importStatus := transaction.ImportStatus
	if importStatus == "" {
		importStatus = "Unmatched"
	}

	_, err := exec.ExecContext(ctx, `
		INSERT INTO transactions (
			transaction_id, source_account_ref, transaction_type, amount, currency,
			transaction_date, status, description, account_id, client_id, import_status
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
		ON CONFLICT (transaction_id) DO UPDATE SET status = EXCLUDED.status
		WHERE transactions.status = 'Pending' AND EXCLUDED.status <> 'Pending'`,
		transaction.TransactionID,
		transaction.AccountID,
		transaction.TransactionType,
		types.NewDecimal(new(decimal.Big).SetFloat64(transaction.Amount)),
		null.StringFrom(transaction.Currency),
		transaction.TransferDate,
		transaction.Status,
		null.StringFrom(transaction.Description),
		null.NewString(transaction.LinkedAccountID, transaction.LinkedAccountID != ""),
		null.NewString(transaction.ClientID, transaction.ClientID != ""),
		importStatus,
	)
	if err != nil {
		return fmt.Errorf("save transaction %q: %w", transaction.TransactionID, err)
	}

	return nil
}
