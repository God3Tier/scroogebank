package repository

import (
	"context"
	"database/sql"
	"time"

	"receiver/src/internal/models"

	"github.com/aarondl/sqlboiler/v4/queries"
	"github.com/lib/pq"
)

// TransactionStats computes the business dashboard's transaction figures in
// the database, so no transaction rows leave the service (ADR-0038).
type TransactionStats interface {
	TransactionStats(ctx context.Context, query models.TransactionStatsQuery) (models.TransactionStats, error)
}

func NewTransactionStats(db *sql.DB) TransactionStats {
	return impl{db}
}

// $1 is NULL for every transaction, otherwise the account IDs.
const statsScope = `($1::uuid[] IS NULL OR account_id = ANY($1::uuid[]))`

// $2 and $3 are the window's first and last date, as Singapore days.
const statsWindow = `transaction_date >= ($2::date)::timestamp AT TIME ZONE 'Asia/Singapore'
	AND transaction_date < ($3::date + 1)::timestamp AT TIME ZONE 'Asia/Singapore'`

// Exactly two decimals, so an empty sum reads "0.00" like any other amount.
const amountSum = `coalesce(sum(amount), 0)::numeric(21, 2)::text`

func (i impl) TransactionStats(ctx context.Context, query models.TransactionStatsQuery) (models.TransactionStats, error) {
	stats := models.TransactionStats{
		ByStatus: map[string]int64{}, ByType: map[string]int64{}, Currencies: []string{}, Daily: []models.DailyTransactions{},
	}
	if !query.All && len(query.AccountIDs) == 0 {
		// An agent with no accounts has no transactions.
		return stats, nil
	}
	var accounts any
	if !query.All {
		accounts = pq.Array(query.AccountIDs)
	}
	from, to := query.From.Format(time.DateOnly), query.To.Format(time.DateOnly)

	var groups []struct {
		Status   string `boil:"status"`
		Type     string `boil:"transaction_type"`
		Currency string `boil:"currency"`
		Count    int64  `boil:"count"`
	}
	if err := queries.Raw(`SELECT status, transaction_type, coalesce(currency, '') AS currency, count(*) AS count
		FROM transactions WHERE `+statsScope+` AND `+statsWindow+` GROUP BY 1, 2, 3 ORDER BY 3`,
		accounts, from, to).Bind(ctx, i.db, &groups); err != nil {
		return models.TransactionStats{}, err
	}
	for _, group := range groups {
		stats.Total += group.Count
		stats.ByStatus[group.Status] += group.Count
		stats.ByType[group.Type] += group.Count
		if n := len(stats.Currencies); n == 0 || stats.Currencies[n-1] != group.Currency {
			stats.Currencies = append(stats.Currencies, group.Currency)
		}
	}

	if err := queries.Raw(`SELECT to_char((transaction_date AT TIME ZONE 'Asia/Singapore')::date, 'YYYY-MM-DD') AS day,
			count(*) AS count, `+amountSum+` AS value
		FROM transactions WHERE `+statsScope+` AND `+statsWindow+` GROUP BY 1 ORDER BY 1`,
		accounts, from, to).Bind(ctx, i.db, &stats.Daily); err != nil {
		return models.TransactionStats{}, err
	}
	// A sum across currencies is a number that looks right and is not.
	if len(stats.Currencies) <= 1 {
		if err := i.db.QueryRowContext(ctx, `SELECT `+amountSum+` FROM transactions WHERE `+statsScope+` AND `+statsWindow,
			accounts, from, to).Scan(&stats.TotalValue); err != nil {
			return models.TransactionStats{}, err
		}
	} else {
		for day := range stats.Daily {
			stats.Daily[day].Value = ""
		}
	}

	if err := i.db.QueryRowContext(ctx, `SELECT count(*) FROM transactions WHERE `+statsScope+`
		AND status = 'Pending' AND transaction_date < now() - interval '24 hours'`, accounts).Scan(&stats.PendingBacklog); err != nil {
		return models.TransactionStats{}, err
	}

	if query.All {
		var unmatched int64
		if err := i.db.QueryRowContext(ctx, `SELECT count(*) FROM transactions WHERE account_id IS NULL
			AND transaction_date >= ($1::date)::timestamp AT TIME ZONE 'Asia/Singapore'
			AND transaction_date < ($2::date + 1)::timestamp AT TIME ZONE 'Asia/Singapore'`,
			from, to).Scan(&unmatched); err != nil {
			return models.TransactionStats{}, err
		}
		stats.Unmatched = &unmatched
	}
	return stats, nil
}
