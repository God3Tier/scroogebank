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
	record := &orm.Transaction{
		TransactionID:    transaction.TransactionID,
		SourceAccountRef: transaction.AccountID,
		TransactionType:  transaction.TransactionType,
		Amount:           types.NewDecimal(new(decimal.Big).SetFloat64(transaction.Amount)),
		Currency:         null.StringFrom(transaction.Currency),
		TransactionDate:  transaction.TransferDate,
		Status:           transaction.Status,
		Description:      null.StringFrom(transaction.Description),
	}

	err := record.Upsert(
		ctx,
		i.db,
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
		),
	)
	if err != nil {
		return fmt.Errorf("save transaction %q: %w", transaction.TransactionID, err)
	}

	return nil
}
