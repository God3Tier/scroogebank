package repository

import (
	"context"
	"fmt"

	"receiver/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

func (i impl) GetTransactionsByAccountID(ctx context.Context, accountID string) (orm.TransactionSlice, error) {
	transactions, err := orm.Transactions(
		qm.Select(
			orm.TransactionTableColumns.TransactionID,
			orm.TransactionTableColumns.SourceAccountRef,
			orm.TransactionTableColumns.ClientID,
			orm.TransactionTableColumns.TransactionType,
			orm.TransactionTableColumns.Amount,
			orm.TransactionTableColumns.Currency,
			orm.TransactionTableColumns.TransactionDate,
			orm.TransactionTableColumns.Status,
			orm.TransactionTableColumns.Description,
			orm.TransactionTableColumns.ImportedAt,
			orm.TransactionTableColumns.ImportStatus,
		),
		qm.Where(`"transactions"."account_id" = ?`, accountID),
		qm.OrderBy("transaction_date DESC, transaction_id"),
	).All(ctx, i.db)
	if err != nil {
		return nil, fmt.Errorf("get transactions for account %q: %w", accountID, err)
	}
	if transactions == nil {
		return orm.TransactionSlice{}, nil
	}
	return transactions, nil
}
