// Package aggregate computes the account figures for the business dashboard
// in the database, so no account rows leave the service (ADR-0033, ADR-0038).
package aggregate

import (
	"context"
	"database/sql"
	"errors"
	"time"

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
	// deposits are summed per currency and never across them (ADR-0062).
	var currencies []struct {
		Currency       string `boil:"currency"`
		Count          int64  `boil:"count"`
		InitialDeposit string `boil:"value"`
	}
	if err := queries.Raw(`SELECT currency, count(*) AS count, `+depositSum+` AS value
		FROM accounts WHERE `+inScope+` AND `+inWindow+` GROUP BY 1 ORDER BY 1`,
		owner, from, to).Bind(ctx, r.db, &currencies); err != nil {
		return models.AccountStats{}, err
	}

	stats := models.AccountStats{
		ByType: map[string]int64{}, ByStatus: map[string]int64{}, ByCurrency: map[string]int64{}, ByBranch: map[string]int64{},
		BookedInitialDeposits: map[string]string{}, Currencies: []string{}, DailyOpened: []models.DailyValue{},
	}
	for _, currency := range currencies {
		stats.Currencies = append(stats.Currencies, currency.Currency)
		stats.OpenedInWindow += currency.Count
		stats.BookedInitialDeposits[currency.Currency] = currency.InitialDeposit
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

	var days []struct {
		Date  string `boil:"day"`
		Count int64  `boil:"count"`
		Value string `boil:"value"`
	}
	if err := queries.Raw(`SELECT to_char(opening_date, 'YYYY-MM-DD') AS day, count(*) AS count, `+depositSum+` AS value
		FROM accounts WHERE `+inScope+` AND `+inWindow+` GROUP BY 1 ORDER BY 1`,
		owner, from, to).Bind(ctx, r.db, &days); err != nil {
		return models.AccountStats{}, err
	}
	for _, day := range days {
		daily := models.DailyValue{Date: day.Date, Count: day.Count}
		// A day's value is one sum, so it is given only when the whole
		// window is in one currency, as the receiver does for transactions.
		if len(stats.Currencies) == 1 {
			value := day.Value
			daily.Value = &value
		}
		stats.DailyOpened = append(stats.DailyOpened, daily)
	}

	if byAgent {
		var agentRows []struct {
			AgentID        string `boil:"agent_id"`
			Currency       string `boil:"currency"`
			AccountsOpened int64  `boil:"accounts_opened"`
			InitialDeposit string `boil:"booked_initial_deposits"`
		}
		if err := queries.Raw(`SELECT owner_agent_id AS agent_id, currency, count(*) AS accounts_opened, `+depositSum+` AS booked_initial_deposits
			FROM accounts WHERE `+inScope+` AND `+inWindow+` GROUP BY 1, 2 ORDER BY 1, 2`,
			owner, from, to).Bind(ctx, r.db, &agentRows); err != nil {
			return models.AccountStats{}, err
		}
		// Rows come ordered by agent, so each agent's currencies are adjacent.
		stats.ByAgent = []models.AgentAccountCounts{}
		for _, row := range agentRows {
			if n := len(stats.ByAgent); n == 0 || stats.ByAgent[n-1].AgentID != row.AgentID {
				stats.ByAgent = append(stats.ByAgent, models.AgentAccountCounts{AgentID: row.AgentID, BookedInitialDeposits: map[string]string{}})
			}
			agent := &stats.ByAgent[len(stats.ByAgent)-1]
			agent.AccountsOpened += row.AccountsOpened
			agent.BookedInitialDeposits[row.Currency] = row.InitialDeposit
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
