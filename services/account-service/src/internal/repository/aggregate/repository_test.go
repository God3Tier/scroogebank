package aggregate

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

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
	assert.Equal(t, map[string]string{"SGD": "350.50"}, stats.BookedInitialDeposits)
	assert.Equal(t, []string{"SGD"}, stats.Currencies)
	assert.Equal(t, []models.DailyValue{
		{Date: "2031-09-01", Count: 1, Value: amount("100.00")},
		{Date: "2031-09-15", Count: 1, Value: amount("250.50")},
	}, stats.DailyOpened)
	assert.Nil(t, stats.ByAgent)
}

func TestAccountStatsForAnAdminGroupsByAgent(t *testing.T) {
	repo := New(openFixtureDB(t))

	stats, err := repo.AccountStats(context.Background(), admin, window(time.September, time.September, 30), true)

	require.NoError(t, err)
	assert.Equal(t, int64(3), stats.OpenedInWindow)
	assert.Equal(t, map[string]string{"SGD": "360.50"}, stats.BookedInitialDeposits)
	assert.Equal(t, []models.AgentAccountCounts{
		{AgentID: "dash-owner-a", AccountsOpened: 2, BookedInitialDeposits: map[string]string{"SGD": "350.50"}},
		{AgentID: "dash-owner-b", AccountsOpened: 1, BookedInitialDeposits: map[string]string{"SGD": "10.00"}},
	}, stats.ByAgent)
}

func TestAccountStatsSumsEachCurrencySeparately(t *testing.T) {
	repo := New(openFixtureDB(t))

	stats, err := repo.AccountStats(context.Background(), ownerC, window(time.November, time.November, 30), false)

	require.NoError(t, err)
	assert.Equal(t, int64(2), stats.Total)
	assert.Equal(t, map[string]int64{"SGD": 1, "USD": 1}, stats.ByCurrency)
	assert.Equal(t, int64(2), stats.OpenedInWindow)
	assert.Equal(t, map[string]string{"SGD": "7.00", "USD": "5.00"}, stats.BookedInitialDeposits)
	assert.Equal(t, []string{"SGD", "USD"}, stats.Currencies)
	// A day's value would be a sum of one or the other, so none is given.
	assert.Equal(t, []models.DailyValue{
		{Date: "2031-11-15", Count: 1},
		{Date: "2031-11-20", Count: 1},
	}, stats.DailyOpened)
}

func TestAccountStatsGroupsEachAgentsDepositsByCurrency(t *testing.T) {
	repo := New(openFixtureDB(t))

	stats, err := repo.AccountStats(context.Background(), admin, window(time.November, time.November, 30), true)

	require.NoError(t, err)
	assert.Equal(t, []models.AgentAccountCounts{
		{AgentID: "dash-owner-c", AccountsOpened: 2, BookedInitialDeposits: map[string]string{"SGD": "7.00", "USD": "5.00"}},
	}, stats.ByAgent)
}

func TestAccountStatsWithNothingOpenedInTheWindow(t *testing.T) {
	repo := New(openFixtureDB(t))

	stats, err := repo.AccountStats(context.Background(), ownerA, window(time.December, time.December, 31), false)

	require.NoError(t, err)
	assert.Equal(t, int64(3), stats.Total)
	assert.Equal(t, int64(0), stats.OpenedInWindow)
	assert.Equal(t, map[string]string{}, stats.BookedInitialDeposits)
	assert.Equal(t, []string{}, stats.Currencies)
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

func amount(value string) *string {
	return &value
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
