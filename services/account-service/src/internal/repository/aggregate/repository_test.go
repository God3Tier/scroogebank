package aggregate

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"

	_ "github.com/lib/pq"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

var (
	ownerA = models.Caller{Subject: "dash-owner-a"}
	ownerC = models.Caller{Subject: "dash-owner-c"}
	admin  = models.Caller{Subject: "dash-admin", Admin: true}
)

func window(fromMonth, toMonth time.Month, toDay int) models.Window {
	return models.Window{From: time.Date(2031, fromMonth, 1, 0, 0, 0, 0, time.UTC), To: time.Date(2031, toMonth, toDay, 0, 0, 0, 0, time.UTC)}
}

func TestAccountStatsForAnAgentCountsOnlyTheirAccounts(t *testing.T) {
	repo := New(openFixtureDB(t))

	stats, err := repo.AccountStats(context.Background(), ownerA, window(time.September, time.September, 30), false)

	require.NoError(t, err)
	// 601, 602 and 603; deleted 604 is left out.
	assert.Equal(t, int64(3), stats.Total)
	assert.Equal(t, map[string]int64{"savings": 2, "checking": 1}, stats.ByType)
	assert.Equal(t, map[string]int64{"active": 2, "closed": 1}, stats.ByStatus)
	assert.Equal(t, map[string]int64{"SGD": 3}, stats.ByCurrency)
	assert.Equal(t, map[string]int64{"B1": 2, "B2": 1}, stats.ByBranch)
	// 603 opened the day before the window.
	assert.Equal(t, int64(2), stats.OpenedInWindow)
	assert.Equal(t, "350.50", stats.BookedInitialDeposits)
	assert.Equal(t, "SGD", stats.Currency)
	assert.Equal(t, []models.DailyValue{
		{Date: "2031-09-01", Count: 1, Value: "100.00"},
		{Date: "2031-09-15", Count: 1, Value: "250.50"},
	}, stats.DailyOpened)
	assert.Nil(t, stats.ByAgent)
}

func TestAccountStatsForAnAdminGroupsByAgent(t *testing.T) {
	repo := New(openFixtureDB(t))

	stats, err := repo.AccountStats(context.Background(), admin, window(time.September, time.September, 30), true)

	require.NoError(t, err)
	assert.Equal(t, int64(3), stats.OpenedInWindow)
	assert.Equal(t, "360.50", stats.BookedInitialDeposits)
	assert.Equal(t, []models.AgentAccountCounts{
		{AgentID: "dash-owner-a", AccountsOpened: 2, BookedInitialDeposits: "350.50"},
		{AgentID: "dash-owner-b", AccountsOpened: 1, BookedInitialDeposits: "10.00"},
	}, stats.ByAgent)
}

func TestAccountStatsRefusesToAddCurrencies(t *testing.T) {
	repo := New(openFixtureDB(t))

	_, err := repo.AccountStats(context.Background(), ownerC, window(time.November, time.November, 30), false)

	assert.ErrorIs(t, err, accounterrors.ErrMultipleCurrencies)
}

func TestAccountStatsWithNothingOpenedInTheWindow(t *testing.T) {
	repo := New(openFixtureDB(t))

	stats, err := repo.AccountStats(context.Background(), ownerA, window(time.December, time.December, 31), false)

	require.NoError(t, err)
	assert.Equal(t, int64(3), stats.Total)
	assert.Equal(t, int64(0), stats.OpenedInWindow)
	assert.Equal(t, "0.00", stats.BookedInitialDeposits)
	assert.Empty(t, stats.Currency)
	assert.NotNil(t, stats.DailyOpened)
	assert.Empty(t, stats.DailyOpened)
}

func TestOwnedAccountIDsLeavesOutDeletedAccounts(t *testing.T) {
	repo := New(openFixtureDB(t))

	ids, err := repo.OwnedAccountIDs(context.Background(), "dash-owner-a")

	require.NoError(t, err)
	assert.Equal(t, []string{
		"00000000-0000-0000-0000-000000000601",
		"00000000-0000-0000-0000-000000000602",
		"00000000-0000-0000-0000-000000000603",
	}, ids)
}

func TestAnAgentWithoutASubjectIsRefused(t *testing.T) {
	repo := New(openFixtureDB(t))

	_, statsErr := repo.AccountStats(context.Background(), models.Caller{}, window(time.September, time.September, 30), false)
	_, idsErr := repo.OwnedAccountIDs(context.Background(), "")

	assert.ErrorIs(t, statsErr, errNoSubject)
	assert.ErrorIs(t, idsErr, errNoSubject)
}

func openFixtureDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := "host=" + envOrDefault("DB_HOST", "localhost") + " port=" + envOrDefault("DB_PORT", "5435") +
		" user=" + envOrDefault("DB_USER", "postgres") + " password=" + envOrDefault("DB_PASSWORD", "postgres") +
		" dbname=" + envOrDefault("DB_NAME", "accounts") + " sslmode=disable"
	db, err := sql.Open("postgres", dsn)
	require.NoError(t, err)
	t.Cleanup(func() { _ = db.Close() })
	require.NoError(t, db.Ping(), "connect to the test database")

	_, currentFile, _, ok := runtime.Caller(0)
	require.True(t, ok, "locate test fixtures")
	fixture, err := os.ReadFile(filepath.Join(filepath.Dir(currentFile), "testcase", "aggregate_accounts.sql"))
	require.NoError(t, err)
	_, err = db.Exec(string(fixture))
	require.NoError(t, err)
	return db
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
