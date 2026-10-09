package aggregate

import (
	"context"
	"testing"
	"time"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	repomock "client-profile-management-service/src/internal/repository/aggregate"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

var (
	agent = models.Caller{Subject: "agent-sub"}
	admin = models.Caller{Subject: "admin-sub", Admin: true}
)

func TestClientStats(t *testing.T) {
	ctx := context.Background()
	window := models.Window{From: time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC), To: time.Date(2026, 9, 30, 0, 0, 0, 0, time.UTC)}
	tests := map[string]struct {
		caller   models.Caller
		from, to string
		groupBy  string
		byAgent  bool
		wantErr  error
	}{
		"agent's own figures":            {caller: agent, from: "2026-09-01", to: "2026-09-30"},
		"admin grouped by agent":         {caller: admin, from: "2026-09-01", to: "2026-09-30", groupBy: "agent", byAgent: true},
		"agent cannot group by agent":    {caller: agent, from: "2026-09-01", to: "2026-09-30", groupBy: "agent", wantErr: clienterrors.ErrForbidden},
		"unknown grouping":               {caller: admin, from: "2026-09-01", to: "2026-09-30", groupBy: "branch", wantErr: clienterrors.ErrBadInput},
		"missing dates":                  {caller: agent, wantErr: clienterrors.ErrBadInput},
		"window running backwards":       {caller: agent, from: "2026-09-30", to: "2026-09-01", wantErr: clienterrors.ErrBadInput},
		"window longer than 366 days":    {caller: agent, from: "2025-09-29", to: "2026-09-30", wantErr: clienterrors.ErrBadInput},
		"window of exactly 366 days":     {caller: agent, from: "2025-09-30", to: "2026-09-30"},
		"date with a time is not a date": {caller: agent, from: "2026-09-01T00:00:00Z", to: "2026-09-30", wantErr: clienterrors.ErrBadInput},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.wantErr == nil {
				want := window
				if test.from != "2026-09-01" {
					want = mustWindow(t, test.from, test.to)
				}
				repo.On("ClientStats", ctx, test.caller, want, test.byAgent).Return(models.ClientStats{Total: 1}, nil).Once()
			}

			got, err := New(repo).ClientStats(ctx, test.caller, test.from, test.to, test.groupBy)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
			assert.Equal(t, int64(1), got.Total)
		})
	}
}

func TestActionItems(t *testing.T) {
	ctx := context.Background()
	now := time.Date(2026, 9, 30, 12, 0, 0, 0, time.UTC)
	tests := map[string]struct {
		staleDays  string
		wantBefore time.Time
		wantErr    error
	}{
		"defaults to seven days": {wantBefore: now.AddDate(0, 0, -7)},
		"takes the given days":   {staleDays: "30", wantBefore: now.AddDate(0, 0, -30)},
		"rejects zero days":      {staleDays: "0", wantErr: clienterrors.ErrBadInput},
		"rejects too many days":  {staleDays: "3651", wantErr: clienterrors.ErrBadInput},
		"rejects a non-number":   {staleDays: "week", wantErr: clienterrors.ErrBadInput},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.wantErr == nil {
				repo.On("StaleUnverifiedClients", ctx, agent, test.wantBefore, ActionItemLimit).
					Return(models.ClientActionItems{StaleUnverifiedTotal: 2}, nil).Once()
			}
			svc := service{repo: repo, now: func() time.Time { return now }}

			got, err := svc.ActionItems(ctx, agent, test.staleDays)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				repo.AssertNotCalled(t, "StaleUnverifiedClients", mock.Anything, mock.Anything, mock.Anything, mock.Anything)
				return
			}
			assert.NoError(t, err)
			assert.Equal(t, int64(2), got.StaleUnverifiedTotal)
		})
	}
}

func TestTeamComparison(t *testing.T) {
	ctx := context.Background()
	// 04:00 on 9 October in Singapore, still 8 October in UTC.
	now := time.Date(2026, 10, 8, 20, 0, 0, 0, time.UTC)
	day := func(month time.Month, d int) time.Time { return time.Date(2026, month, d, 0, 0, 0, 0, time.UTC) }
	median := 2.5
	tests := map[string]struct {
		days       string
		cohortSize int64
		wantFrom   time.Time
		wantDays   int
		wantMedian *float64
		wantErr    error
	}{
		"defaults to 30 days ending today in Singapore": {cohortSize: 5, wantFrom: day(time.September, 10), wantDays: 30, wantMedian: &median},
		"takes 7 days":                    {days: "7", cohortSize: 6, wantFrom: day(time.October, 3), wantDays: 7, wantMedian: &median},
		"takes 90 days":                   {days: "90", cohortSize: 6, wantFrom: day(time.July, 12), wantDays: 90, wantMedian: &median},
		"hides the median below 5 agents": {days: "30", cohortSize: 4, wantFrom: day(time.September, 10), wantDays: 30},
		"refuses other windows":           {days: "14", wantErr: clienterrors.ErrBadInput},
		"refuses a non-number":            {days: "month", wantErr: clienterrors.ErrBadInput},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.wantErr == nil {
				window := models.Window{From: test.wantFrom, To: day(time.October, 9)}
				repo.On("TeamOnboarding", ctx, "agent-sub", window, now.AddDate(-1, 0, 0)).
					Return(models.TeamOnboarding{ClientsOnboarded: 3, Median: &median, CohortSize: test.cohortSize}, nil).Once()
			}
			svc := service{repo: repo, now: func() time.Time { return now }}

			got, err := svc.TeamComparison(ctx, agent, test.days)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
			assert.Equal(t, models.TeamComparison{
				Days: test.wantDays, From: test.wantFrom.Format(time.DateOnly), To: "2026-10-09",
				ClientsOnboarded: 3, TeamMedian: test.wantMedian, CohortSize: test.cohortSize,
			}, got)
		})
	}
}

func mustWindow(t *testing.T, from, to string) models.Window {
	t.Helper()
	window, err := ParseWindow(from, to)
	if err != nil {
		t.Fatal(err)
	}
	return window
}
