// Package aggregate computes the client figures for the business dashboard in
// the database, so no client rows leave the service (ADR-0033, ADR-0038).
package aggregate

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"client-profile-management-service/src/internal/models"

	"github.com/aarondl/sqlboiler/v4/queries"
)

type Repository interface {
	ClientStats(ctx context.Context, caller models.Caller, window models.Window, byAgent bool) (models.ClientStats, error)
	StaleUnverifiedClients(ctx context.Context, caller models.Caller, createdBefore time.Time, limit int) (models.ClientActionItems, error)
	// TeamOnboarding counts the agent's clients created in the window, and the
	// median of the same count across every agent who created a client, not
	// since deleted, on or after activeSince.
	TeamOnboarding(ctx context.Context, agentID string, window models.Window, activeSince time.Time) (models.TeamOnboarding, error)
}

type repository struct {
	db *sql.DB
}

func New(db *sql.DB) Repository {
	return repository{db: db}
}

// Every query takes the agent as $1: NULL for an admin, otherwise the agent's
// sub. Deleted clients are left out (ADR-0036).
const inScope = `deleted_at IS NULL AND ($1::text IS NULL OR assigned_agent_id = $1::text)`

// The window's days are Singapore days: $2 and $3 are its first and last date.
const inWindow = `created_at >= ($2::date)::timestamp AT TIME ZONE 'Asia/Singapore'
	AND created_at < ($3::date + 1)::timestamp AT TIME ZONE 'Asia/Singapore'`

const notVerified = `upper(verification_status) <> 'VERIFIED'`

var errNoSubject = errors.New("agent caller has no subject")

func (r repository) ClientStats(ctx context.Context, caller models.Caller, window models.Window, byAgent bool) (models.ClientStats, error) {
	agent, err := agentArg(caller)
	if err != nil {
		return models.ClientStats{}, err
	}
	stats := models.ClientStats{ByVerificationStatus: map[string]int64{}, DailyCreated: []models.DailyCount{}}

	var statuses []struct {
		Status string `boil:"status"`
		Count  int64  `boil:"count"`
	}
	if err := queries.Raw(`SELECT upper(verification_status) AS status, count(*) AS count
		FROM clients WHERE `+inScope+` GROUP BY 1`, agent).Bind(ctx, r.db, &statuses); err != nil {
		return models.ClientStats{}, err
	}
	for _, status := range statuses {
		stats.ByVerificationStatus[status.Status] = status.Count
		stats.Total += status.Count
	}

	from, to := window.From.Format(time.DateOnly), window.To.Format(time.DateOnly)
	var days []struct {
		Date  string `boil:"day"`
		Count int64  `boil:"count"`
	}
	if err := queries.Raw(`SELECT to_char((created_at AT TIME ZONE 'Asia/Singapore')::date, 'YYYY-MM-DD') AS day, count(*) AS count
		FROM clients WHERE `+inScope+` AND `+inWindow+` GROUP BY 1 ORDER BY 1`, agent, from, to).Bind(ctx, r.db, &days); err != nil {
		return models.ClientStats{}, err
	}
	for _, day := range days {
		stats.DailyCreated = append(stats.DailyCreated, models.DailyCount{Date: day.Date, Count: day.Count})
		stats.CreatedInWindow += day.Count
	}

	if byAgent {
		stats.ByAgent = []models.AgentClientCounts{}
		if err := queries.Raw(`SELECT assigned_agent_id AS agent_id, count(*) AS clients_onboarded,
				count(*) FILTER (WHERE upper(verification_status) = 'VERIFIED') AS verified,
				count(*) FILTER (WHERE `+notVerified+`) AS unverified
			FROM clients WHERE `+inScope+` AND `+inWindow+` GROUP BY 1 ORDER BY 1`, agent, from, to).Bind(ctx, r.db, &stats.ByAgent); err != nil {
			return models.ClientStats{}, err
		}
	}
	return stats, nil
}

func (r repository) StaleUnverifiedClients(ctx context.Context, caller models.Caller, createdBefore time.Time, limit int) (models.ClientActionItems, error) {
	agent, err := agentArg(caller)
	if err != nil {
		return models.ClientActionItems{}, err
	}
	items := models.ClientActionItems{StaleUnverified: []models.ClientRef{}}
	if err := queries.Raw(`SELECT client_id, first_name, last_name, created_at
		FROM clients WHERE `+inScope+` AND `+notVerified+` AND created_at < $2
		ORDER BY created_at, client_id LIMIT $3`, agent, createdBefore, limit).Bind(ctx, r.db, &items.StaleUnverified); err != nil {
		return models.ClientActionItems{}, err
	}
	err = r.db.QueryRowContext(ctx, `SELECT count(*) FROM clients WHERE `+inScope+` AND `+notVerified+` AND created_at < $2`,
		agent, createdBefore).Scan(&items.StaleUnverifiedTotal)
	return items, err
}

// TeamOnboarding returns only the agent's own count and the group's median
// and size. client-service has no list of enabled agents, so the group is the
// agents with a recent client: an agent who left still counts until a year
// after their last client, and an admin who created a client counts too
// (ADR-0038).
func (r repository) TeamOnboarding(ctx context.Context, agentID string, window models.Window, activeSince time.Time) (models.TeamOnboarding, error) {
	if agentID == "" {
		return models.TeamOnboarding{}, errNoSubject
	}
	from, to := window.From.Format(time.DateOnly), window.To.Format(time.DateOnly)
	var team models.TeamOnboarding
	var median sql.NullFloat64
	// Every member of the group counts, including those who onboarded no one
	// in the window: the LEFT JOIN gives them a zero.
	if err := r.db.QueryRowContext(ctx, `WITH cohort AS (
			SELECT DISTINCT assigned_agent_id AS agent_id FROM clients
			WHERE deleted_at IS NULL AND created_at >= $3
		), onboarded AS (
			SELECT cohort.agent_id, count(clients.client_id) AS clients
			FROM cohort LEFT JOIN clients ON clients.assigned_agent_id = cohort.agent_id
				AND clients.deleted_at IS NULL
				AND clients.created_at >= ($1::date)::timestamp AT TIME ZONE 'Asia/Singapore'
				AND clients.created_at < ($2::date + 1)::timestamp AT TIME ZONE 'Asia/Singapore'
			GROUP BY cohort.agent_id
		)
		SELECT count(*), percentile_cont(0.5) WITHIN GROUP (ORDER BY clients) FROM onboarded`,
		from, to, activeSince).Scan(&team.CohortSize, &median); err != nil {
		return models.TeamOnboarding{}, err
	}
	if median.Valid {
		team.Median = &median.Float64
	}
	err := r.db.QueryRowContext(ctx, `SELECT count(*) FROM clients WHERE deleted_at IS NULL AND assigned_agent_id = $1
		AND created_at >= ($2::date)::timestamp AT TIME ZONE 'Asia/Singapore'
		AND created_at < ($3::date + 1)::timestamp AT TIME ZONE 'Asia/Singapore'`,
		agentID, from, to).Scan(&team.ClientsOnboarded)
	return team, err
}

// agentArg is the $1 every query filters on. An agent without a subject would
// otherwise look like an admin, so it is refused.
func agentArg(caller models.Caller) (any, error) {
	if caller.Admin {
		return nil, nil
	}
	if caller.Subject == "" {
		return nil, errNoSubject
	}
	return caller.Subject, nil
}
