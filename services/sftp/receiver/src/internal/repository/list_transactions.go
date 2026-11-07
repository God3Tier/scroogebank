package repository

import (
	"context"
	"fmt"

	"receiver/src/internal/models"
	"receiver/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

// ListTransactions leaves out rows not linked to an account, as the
// per-account read does. Keyset paging on (transaction_date, transaction_id)
// stays fast however far back the caller pages (ADR-0043).
func (i impl) ListTransactions(ctx context.Context, query models.TransactionListQuery) (orm.TransactionSlice, error) {
	mods := []qm.QueryMod{
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
			orm.TransactionTableColumns.AccountID,
		),
		qm.Where(orm.TransactionTableColumns.AccountID + " IS NOT NULL"),
	}
	if query.Status != "" {
		mods = append(mods, qm.Where(orm.TransactionTableColumns.Status+" = ?", query.Status))
	}
	if query.After != nil {
		mods = append(mods, qm.Where(
			"("+orm.TransactionTableColumns.TransactionDate+", "+orm.TransactionTableColumns.TransactionID+") < (?, ?)",
			query.After.Date, query.After.ID,
		))
	}
	mods = append(mods,
		qm.OrderBy("transaction_date DESC, transaction_id DESC"),
		qm.Limit(query.Limit),
	)

	transactions, err := orm.Transactions(mods...).All(ctx, i.db)
	if err != nil {
		return nil, fmt.Errorf("list transactions: %w", err)
	}
	if transactions == nil {
		return orm.TransactionSlice{}, nil
	}
	return transactions, nil
}
