package aggregate

import (
	"context"
	"errors"
	"testing"

	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	repomock "account-service/src/internal/repository/aggregate"

	"github.com/stretchr/testify/assert"
)

var (
	agent = models.Caller{Subject: "agent-sub"}
	admin = models.Caller{Subject: "admin-sub", Admin: true}
)

func TestAccountStats(t *testing.T) {
	ctx := context.Background()
	tests := map[string]struct {
		caller   models.Caller
		from, to string
		groupBy  string
		byAgent  bool
		wantErr  error
	}{
		"agent's own figures":         {caller: agent, from: "2026-09-01", to: "2026-09-30"},
		"admin grouped by agent":      {caller: admin, from: "2026-09-01", to: "2026-09-30", groupBy: "agent", byAgent: true},
		"agent cannot group by agent": {caller: agent, from: "2026-09-01", to: "2026-09-30", groupBy: "agent", wantErr: accounterrors.ErrForbidden},
		"unknown grouping":            {caller: admin, from: "2026-09-01", to: "2026-09-30", groupBy: "branch", wantErr: accounterrors.ErrBadInput},
		"missing dates":               {caller: agent, wantErr: accounterrors.ErrBadInput},
		"window running backwards":    {caller: agent, from: "2026-09-30", to: "2026-09-01", wantErr: accounterrors.ErrBadInput},
		"window longer than 366 days": {caller: agent, from: "2025-09-29", to: "2026-09-30", wantErr: accounterrors.ErrBadInput},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.wantErr == nil {
				window, err := ParseWindow(test.from, test.to)
				assert.NoError(t, err)
				repo.On("AccountStats", ctx, test.caller, window, test.byAgent).Return(models.AccountStats{Total: 1}, nil).Once()
			}

			got, err := New(repo).AccountStats(ctx, test.caller, test.from, test.to, test.groupBy)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
			assert.Equal(t, int64(1), got.Total)
		})
	}
}

func TestOwnedAccountIDs(t *testing.T) {
	ctx := context.Background()
	repo := repomock.NewMockRepository(t)
	repo.On("OwnedAccountIDs", ctx, "agent-sub").Return([]string{"a1", "a2"}, nil).Once()

	got, err := New(repo).OwnedAccountIDs(ctx, agent)

	assert.NoError(t, err)
	assert.Equal(t, models.AccountIDs{AccountIDs: []string{"a1", "a2"}}, got)
}

func TestOwnedAccountIDsPassesOnErrors(t *testing.T) {
	ctx := context.Background()
	repo := repomock.NewMockRepository(t)
	databaseErr := errors.New("database unavailable")
	repo.On("OwnedAccountIDs", ctx, "agent-sub").Return(nil, databaseErr).Once()

	_, err := New(repo).OwnedAccountIDs(ctx, agent)

	assert.ErrorIs(t, err, databaseErr)
}
