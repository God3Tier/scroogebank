package repository

import (
	"context"
	"fmt"

	"receiver/src/internal/models"
	"receiver/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

// ListClientHealthTransactions returns matched rows linked to both a client
// and an account in the requested time window.
func (i impl) ListClientHealthTransactions(ctx context.Context, query models.ClientHealthTransactionQuery) ([]models.ClientHealthTransaction, error) {
	mods := []qm.QueryMod{
		qm.Select(
			orm.TransactionTableColumns.TransactionID,
			orm.TransactionTableColumns.ClientID,
			orm.TransactionTableColumns.AccountID,
			orm.TransactionTableColumns.TransactionType,
			orm.TransactionTableColumns.Amount,
			orm.TransactionTableColumns.Currency,
			orm.TransactionTableColumns.TransactionDate,
			orm.TransactionTableColumns.Status,
			orm.TransactionTableColumns.ActivityKind,
			orm.TransactionTableColumns.SourceFingerprint,
		),
		qm.Where(orm.TransactionTableColumns.ImportStatus+" = ?", "Matched"),
		qm.Where(orm.TransactionTableColumns.ClientID + " IS NOT NULL"),
		qm.Where(orm.TransactionTableColumns.AccountID + " IS NOT NULL"),
		qm.Where(orm.TransactionTableColumns.TransactionDate+" >= ?", query.From),
		qm.Where(orm.TransactionTableColumns.TransactionDate+" < ?", query.To),
	}
	if query.After != nil {
		mods = append(mods, qm.Where(
			"("+orm.TransactionTableColumns.TransactionDate+", "+orm.TransactionTableColumns.TransactionID+") < (?, ?)",
			query.After.Date, query.After.ID,
		))
	}
	mods = append(mods,
		qm.OrderBy(orm.TransactionTableColumns.TransactionDate+" DESC, "+orm.TransactionTableColumns.TransactionID+" DESC"),
		qm.Limit(query.Limit),
	)
	transactions, err := orm.Transactions(mods...).All(ctx, i.db)
	if err != nil {
		return nil, fmt.Errorf("list Client Health Monitor transactions: %w", err)
	}
	rows := make([]models.ClientHealthTransaction, 0, len(transactions))
	for _, transaction := range transactions {
		row := models.ClientHealthTransaction{
			TransactionID:   transaction.TransactionID,
			ClientID:        transaction.ClientID.String,
			AccountID:       transaction.AccountID.String,
			TransactionType: transaction.TransactionType,
			Amount:          transaction.Amount.String(),
			Currency:        transaction.Currency.String,
			TransactionDate: transaction.TransactionDate,
			Status:          transaction.Status,
			ActivityKind:    transaction.ActivityKind,
		}
		if transaction.SourceFingerprint.Valid {
			fingerprint := transaction.SourceFingerprint.String
			row.SourceFingerprint = &fingerprint
		}
		rows = append(rows, row)
	}
	return rows, nil
}
