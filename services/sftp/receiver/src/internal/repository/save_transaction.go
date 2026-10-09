package repository

import (
	"context"
	"fmt"
	"receiver/src/internal/models"
	"receiver/src/internal/repository/orm"

	"github.com/aarondl/null/v8"
	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/types"
	"github.com/ericlagergren/decimal"
)

func (i impl) SaveTransaction(ctx context.Context, transaction models.Transaction) error {
	return saveTransaction(ctx, i.db, transaction)
}

// saveTransaction upserts one transaction through exec, which is the database
// or an import's transaction.
func saveTransaction(ctx context.Context, exec boil.ContextExecutor, transaction models.Transaction) error {
	importStatus := transaction.ImportStatus
	if importStatus == "" {
		importStatus = "Unmatched"
	}
	record := &orm.Transaction{
		TransactionID:    transaction.TransactionID,
		SourceAccountRef: transaction.AccountID,
		TransactionType:  transaction.TransactionType,
		Amount:           types.NewDecimal(new(decimal.Big).SetFloat64(transaction.Amount)),
		Currency:         null.StringFrom(transaction.Currency),
		TransactionDate:  transaction.TransferDate,
		Status:           transaction.Status,
		Description:      null.StringFrom(transaction.Description),
		AccountID:        null.NewString(transaction.LinkedAccountID, transaction.LinkedAccountID != ""),
		ClientID:         null.NewString(transaction.ClientID, transaction.ClientID != ""),
		ImportStatus:     importStatus,
	}

	err := record.Upsert(
		ctx,
		exec,
		false,
		[]string{orm.TransactionColumns.TransactionID},
		boil.None(),
		boil.Whitelist(
			orm.TransactionColumns.TransactionID,
			orm.TransactionColumns.SourceAccountRef,
			orm.TransactionColumns.TransactionType,
			orm.TransactionColumns.Amount,
			orm.TransactionColumns.Currency,
			orm.TransactionColumns.TransactionDate,
			orm.TransactionColumns.Status,
			orm.TransactionColumns.Description,
			orm.TransactionColumns.AccountID,
			orm.TransactionColumns.ClientID,
			orm.TransactionColumns.ImportStatus,
		),
	)
	if err != nil {
		return fmt.Errorf("save transaction %q: %w", transaction.TransactionID, err)
	}

	return nil
}
