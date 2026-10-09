// Package aggregate serves the business dashboard's client figures. Each
// figure is scoped from the verified caller, so an agent has no parameter
// that widens what they see (ADR-0039).
package aggregate

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"client-profile-management-service/src/internal/encryption"
	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/repository/aggregate"
)

const (
	// MaxWindowDays is one day more than the dashboard allows, so the
	// dashboard's own limit is always the one a user meets.
	MaxWindowDays    = 366
	DefaultStaleDays = 7
	MaxStaleDays     = 3650
	// ActionItemLimit caps each work-queue list; the total is still counted.
	ActionItemLimit = 50
	// MinCohort is the smallest comparison group whose median is shown. With
	// fewer agents, a median next to the caller's own figure narrows down the
	// others' figures too far (ADR-0038).
	MinCohort = 5
	// DefaultComparisonDays is the team comparison's window when none is given.
	DefaultComparisonDays = 30
)

// comparisonDays are the only windows a team comparison covers, each ending
// today. Medians over many arbitrary windows could be compared with each
// other to narrow down individual agents' figures.
var comparisonDays = map[int]bool{7: true, 30: true, 90: true}

// singapore is the bank's business day. Singapore has had no daylight saving
// since 1982, so a fixed offset needs no time zone database in the image.
var singapore = time.FixedZone("SGT", 8*60*60)

type Service interface {
	// ClientStats takes the window's first and last date (YYYY-MM-DD).
	// groupBy is empty or "agent"; only admins may group by agent.
	ClientStats(ctx context.Context, caller models.Caller, from, to, groupBy string) (models.ClientStats, error)
	// ActionItems lists clients still not verified staleDays after creation.
	// An empty staleDays means DefaultStaleDays.
	ActionItems(ctx context.Context, caller models.Caller, staleDays string) (models.ClientActionItems, error)
	// TeamComparison compares the caller's clients onboarded over the last
	// days (7, 30 or 90, ending today; empty means 30) with the median of
	// the agents who created a client in the last 12 months.
	TeamComparison(ctx context.Context, caller models.Caller, days string) (models.TeamComparison, error)
}

type service struct {
	repo      aggregate.Repository
	protector encryption.Protector
	now       func() time.Time
}

// New serves the figures from repo. protector decrypts the client names that
// the action items list (ADR-0030).
func New(repo aggregate.Repository, protector encryption.Protector) Service {
	return service{repo: repo, protector: protector, now: time.Now}
}

func (s service) ClientStats(ctx context.Context, caller models.Caller, from, to, groupBy string) (models.ClientStats, error) {
	window, err := ParseWindow(from, to)
	if err != nil {
		return models.ClientStats{}, err
	}
	switch groupBy {
	case "":
	case "agent":
		// Per-agent figures rank named staff, so only admins see them.
		if !caller.Admin {
			return models.ClientStats{}, fmt.Errorf("%w: only admins can group by agent", clienterrors.ErrForbidden)
		}
	default:
		return models.ClientStats{}, fmt.Errorf("%w: groupBy must be empty or agent", clienterrors.ErrBadInput)
	}
	return s.repo.ClientStats(ctx, caller, window, groupBy == "agent")
}

func (s service) ActionItems(ctx context.Context, caller models.Caller, staleDays string) (models.ClientActionItems, error) {
	days := DefaultStaleDays
	if staleDays != "" {
		parsed, err := strconv.Atoi(staleDays)
		if err != nil || parsed < 1 || parsed > MaxStaleDays {
			return models.ClientActionItems{}, fmt.Errorf("%w: staleDays must be 1 to %d", clienterrors.ErrBadInput, MaxStaleDays)
		}
		days = parsed
	}
	items, err := s.repo.StaleUnverifiedClients(ctx, caller, s.now().AddDate(0, 0, -days), ActionItemLimit)
	if err != nil {
		return models.ClientActionItems{}, err
	}
	// Names are stored encrypted with each client's own data key.
	for i := range items.StaleUnverified {
		if err := s.decryptName(ctx, &items.StaleUnverified[i]); err != nil {
			return models.ClientActionItems{}, err
		}
	}
	return items, nil
}

func (s service) decryptName(ctx context.Context, client *models.ClientRef) error {
	key, err := s.protector.Key(ctx, client.ClientID)
	if err != nil {
		return fmt.Errorf("data key for client %s: %w", client.ClientID, err)
	}
	if client.FirstName, err = key.Decrypt(models.FieldFirstName, client.FirstName); err != nil {
		return err
	}
	client.LastName, err = key.Decrypt(models.FieldLastName, client.LastName)
	return err
}

func (s service) TeamComparison(ctx context.Context, caller models.Caller, days string) (models.TeamComparison, error) {
	span := DefaultComparisonDays
	if days != "" {
		parsed, err := strconv.Atoi(days)
		if err != nil || !comparisonDays[parsed] {
			return models.TeamComparison{}, fmt.Errorf("%w: days must be 7, 30 or 90", clienterrors.ErrBadInput)
		}
		span = parsed
	}
	now := s.now()
	today := now.In(singapore)
	to := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, time.UTC)
	from := to.AddDate(0, 0, 1-span)

	team, err := s.repo.TeamOnboarding(ctx, caller.Subject, models.Window{From: from, To: to}, now.AddDate(-1, 0, 0))
	if err != nil {
		return models.TeamComparison{}, err
	}
	comparison := models.TeamComparison{
		Days:             span,
		From:             from.Format(time.DateOnly),
		To:               to.Format(time.DateOnly),
		ClientsOnboarded: team.ClientsOnboarded,
		CohortSize:       team.CohortSize,
	}
	// Hidden here, where the figures are, so no caller can receive it.
	if team.CohortSize >= MinCohort {
		comparison.TeamMedian = team.Median
	}
	return comparison, nil
}

// ParseWindow reads a window's first and last date, both included.
func ParseWindow(from, to string) (models.Window, error) {
	start, fromErr := time.Parse(time.DateOnly, from)
	end, toErr := time.Parse(time.DateOnly, to)
	if fromErr != nil || toErr != nil {
		return models.Window{}, fmt.Errorf("%w: from and to must be dates (YYYY-MM-DD)", clienterrors.ErrBadInput)
	}
	if end.Before(start) || end.Sub(start) >= MaxWindowDays*24*time.Hour {
		return models.Window{}, fmt.Errorf("%w: the window must run forwards and span at most %d days", clienterrors.ErrBadInput, MaxWindowDays)
	}
	return models.Window{From: start, To: end}, nil
}
