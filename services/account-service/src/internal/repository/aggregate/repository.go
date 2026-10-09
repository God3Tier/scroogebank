// Package aggregate computes the account figures for the business dashboard
// in the database, so no account rows leave the service (ADR-0033, ADR-0038).
package aggregate

import (
	"context"
	"database/sql"
	"errors"
	"time"

	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"

	"github.com/aarondl/sqlboiler/v4/queries"
)

type Repository interface {
	AccountStats(ctx context.Context, caller models.Caller, window models.Window, byAgent bool) (models.AccountStats, error)
	OwnedAccountIDs(ctx context.Context, ownerAgentID string) ([]string, error)
}

type repository struct {
	db *sql.DB
}

func New(db *sql.DB) Repository {
	return repository{db: db}
}

// Every query takes the owner as $1: NULL for an admin, otherwise the agent's
// sub. Deleted accounts are left out (ADR-0036).
const inScope = `deleted_at IS NULL AND ($1::text IS NULL OR owner_agent_id = $1::text)`

// $2 and $3 are the window's first and last date.
const inWindow = `opening_date BETWEEN $2::date AND $3::date`

// Exactly two decimals, so an empty sum reads "0.00" like any other amount.
const depositSum = `coalesce(sum(initial_deposit), 0)::numeric(21, 2)::text`

var errNoSubject = errors.New("agent caller has no subject")

func (r repository) AccountStats(ctx context.Context, caller models.Caller, window models.Window, byAgent bool) (models.AccountStats, error) {
	owner, err := ownerArg(caller)
	if err != nil {
		return models.AccountStats{}, err
	}
	from, to := window.From.Format(time.DateOnly), window.To.Format(time.DateOnly)

	// A sum across currencies is a number that looks right and is not, so
	// the figures are refused instead (ADR-0038).
	var currencies []struct {
		Currency string `boil:"currency"`
	}
	if err := queries.Raw(`SELECT currency FROM accounts WHERE `+inScope+` AND `+inWindow+` GROUP BY 1 ORDER BY 1`,
		owner, from, to).Bind(ctx, r.db, &currencies); err != nil {
		return models.AccountStats{}, err
	}
	if len(currencies) > 1 {
		return models.AccountStats{}, accounterrors.ErrMultipleCurrencies
	}

	stats := models.AccountStats{
		ByType: map[string]int64{}, ByStatus: map[string]int64{}, ByCurrency: map[string]int64{}, ByBranch: map[string]int64{},
		BookedInitialDeposits: "0.00", DailyOpened: []models.DailyValue{},
	}
	if len(currencies) == 1 {
		stats.Currency = currencies[0].Currency
	}

	var groups []struct {
		Type     string `boil:"account_type"`
		Status   string `boil:"status"`
		Currency string `boil:"currency"`
		Branch   string `boil:"branch_id"`
		Count    int64  `boil:"count"`
	}
	if err := queries.Raw(`SELECT account_type, status, currency, branch_id, count(*) AS count
		FROM accounts WHERE `+inScope+` GROUP BY 1, 2, 3, 4`, owner).Bind(ctx, r.db, &groups); err != nil {
		return models.AccountStats{}, err
	}
	for _, group := range groups {
		stats.Total += group.Count
		stats.ByType[group.Type] += group.Count
		stats.ByStatus[group.Status] += group.Count
		stats.ByCurrency[group.Currency] += group.Count
		stats.ByBranch[group.Branch] += group.Count
	}

	if err := queries.Raw(`SELECT to_char(opening_date, 'YYYY-MM-DD') AS day, count(*) AS count, `+depositSum+` AS value
		FROM accounts WHERE `+inScope+` AND `+inWindow+` GROUP BY 1 ORDER BY 1`,
		owner, from, to).Bind(ctx, r.db, &stats.DailyOpened); err != nil {
		return models.AccountStats{}, err
	}
	if err := r.db.QueryRowContext(ctx, `SELECT count(*), `+depositSum+` FROM accounts WHERE `+inScope+` AND `+inWindow,
		owner, from, to).Scan(&stats.OpenedInWindow, &stats.BookedInitialDeposits); err != nil {
		return models.AccountStats{}, err
	}

	if byAgent {
		stats.ByAgent = []models.AgentAccountCounts{}
		if err := queries.Raw(`SELECT owner_agent_id AS agent_id, count(*) AS accounts_opened, `+depositSum+` AS booked_initial_deposits
			FROM accounts WHERE `+inScope+` AND `+inWindow+` GROUP BY 1 ORDER BY 1`,
			owner, from, to).Bind(ctx, r.db, &stats.ByAgent); err != nil {
			return models.AccountStats{}, err
		}
	}
	return stats, nil
}

func (r repository) OwnedAccountIDs(ctx context.Context, ownerAgentID string) ([]string, error) {
	if ownerAgentID == "" {
		return nil, errNoSubject
	}
	rows, err := r.db.QueryContext(ctx,
		`SELECT id FROM accounts WHERE deleted_at IS NULL AND owner_agent_id = $1 ORDER BY id`, ownerAgentID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// ownerArg is the $1 every stats query filters on. An agent without a subject
// would otherwise look like an admin, so it is refused.
func ownerArg(caller models.Caller) (any, error) {
	if caller.Admin {
		return nil, nil
	}
	if caller.Subject == "" {
		return nil, errNoSubject
	}
	return caller.Subject, nil
}
