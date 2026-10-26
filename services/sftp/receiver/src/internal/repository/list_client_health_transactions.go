package repository

import (
	"context"
	"fmt"

	"receiver/src/internal/models"

	"github.com/aarondl/sqlboiler/v4/queries"
)

// ListClientHealthTransactions returns only matched rows linked to both a
// client and an account. The projection is deliberately narrower than the
// admin list (Client Health Monitor transaction contract).
func (i impl) ListClientHealthTransactions(ctx context.Context, query models.ClientHealthTransactionQuery) ([]models.ClientHealthTransaction, error) {
	rows := make([]models.ClientHealthTransaction, 0)
	statement := `SELECT transaction_id, client_id::text, account_id::text,
		transaction_type, amount::numeric(18, 2)::text AS amount,
		coalesce(currency, '') AS currency, transaction_date, status,
		activity_kind, source_fingerprint
		FROM transactions
		WHERE import_status = 'Matched' AND client_id IS NOT NULL AND account_id IS NOT NULL
		AND client_id = $1::uuid AND transaction_date >= $2 AND transaction_date < $3`
	args := []any{query.ClientID, query.From, query.To}
	if query.After != nil {
		statement += ` AND (transaction_date, transaction_id) < ($4, $5)`
		args = append(args, query.After.Date, query.After.ID)
	}
	limitPosition := len(args) + 1
	statement += fmt.Sprintf(` ORDER BY transaction_date DESC, transaction_id DESC LIMIT $%d`, limitPosition)
	args = append(args, query.Limit)
	if err := queries.Raw(statement, args...).Bind(ctx, i.db, &rows); err != nil {
		return nil, fmt.Errorf("list Client Health Monitor transactions: %w", err)
	}
	return rows, nil
}
