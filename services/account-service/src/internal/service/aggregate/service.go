// Package aggregate serves the business dashboard's account figures. Each
// figure is scoped from the verified caller, so an agent has no parameter
// that widens what they see (ADR-0039).
package aggregate

import (
	"context"
	"fmt"
	"time"

	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	"account-service/src/internal/repository/aggregate"
)

// MaxWindowDays is one day more than the dashboard allows, so the dashboard's
// own limit is always the one a user meets.
const MaxWindowDays = 366

type Service interface {
	// AccountStats takes the window's first and last date (YYYY-MM-DD).
	// groupBy is empty or "agent"; only admins may group by agent.
	AccountStats(ctx context.Context, caller models.Caller, from, to, groupBy string) (models.AccountStats, error)
	// OwnedAccountIDs lists the accounts the caller owns, whatever their role.
	// user-service uses it to scope an agent's transaction figures.
	OwnedAccountIDs(ctx context.Context, caller models.Caller) (models.AccountIDs, error)
}

type service struct {
	repo aggregate.Repository
}

func New(repo aggregate.Repository) Service {
	return service{repo: repo}
}

func (s service) AccountStats(ctx context.Context, caller models.Caller, from, to, groupBy string) (models.AccountStats, error) {
	window, err := ParseWindow(from, to)
	if err != nil {
		return models.AccountStats{}, err
	}
	switch groupBy {
	case "":
	case "agent":
		// Per-agent figures rank named staff, so only admins see them.
		if !caller.Admin {
			return models.AccountStats{}, fmt.Errorf("%w: only admins can group by agent", accounterrors.ErrForbidden)
		}
	default:
		return models.AccountStats{}, fmt.Errorf("%w: groupBy must be empty or agent", accounterrors.ErrBadInput)
	}
	return s.repo.AccountStats(ctx, caller, window, groupBy == "agent")
}

func (s service) OwnedAccountIDs(ctx context.Context, caller models.Caller) (models.AccountIDs, error) {
	ids, err := s.repo.OwnedAccountIDs(ctx, caller.Subject)
	if err != nil {
		return models.AccountIDs{}, err
	}
	return models.AccountIDs{AccountIDs: ids}, nil
}

// ParseWindow reads a window's first and last date, both included.
func ParseWindow(from, to string) (models.Window, error) {
	start, fromErr := time.Parse(time.DateOnly, from)
	end, toErr := time.Parse(time.DateOnly, to)
	if fromErr != nil || toErr != nil {
		return models.Window{}, fmt.Errorf("%w: from and to must be dates (YYYY-MM-DD)", accounterrors.ErrBadInput)
	}
	if end.Before(start) || end.Sub(start) >= MaxWindowDays*24*time.Hour {
		return models.Window{}, fmt.Errorf("%w: the window must run forwards and span at most %d days", accounterrors.ErrBadInput, MaxWindowDays)
	}
	return models.Window{From: start, To: end}, nil
}
