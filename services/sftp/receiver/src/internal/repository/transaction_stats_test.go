package repository

import (
	"context"
	"database/sql"
	"testing"
	"time"

	"receiver/src/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	statsAccountA = "a1000000-0000-0000-0000-000000000001"
	statsAccountB = "b1000000-0000-0000-0000-000000000001"
	statsAccountC = "c1000000-0000-0000-0000-000000000001"
)

// Own transaction IDs and dates in 2031, so other tests' rows never land in
// these accounts or windows. Times are Singapore time (+08).
func loadStatsFixture(t *testing.T, db *sql.DB) {
	t.Helper()
	_, err := db.Exec(`DELETE FROM transactions WHERE transaction_id LIKE 'dash-%';
		INSERT INTO transactions (transaction_id, source_account_ref, account_id, transaction_type, amount, currency,
			transaction_date, status, import_status) VALUES
		('dash-t1', '` + statsAccountA + `', '` + statsAccountA + `', 'Deposit', 100.00, 'SGD', '2031-09-02 10:00:00+08', 'Completed', 'Matched'),
		('dash-t2', '` + statsAccountA + `', '` + statsAccountA + `', 'Withdrawal', 40.50, 'SGD', '2031-09-02 23:30:00+08', 'Failed', 'Matched'),
		-- Midnight Singapore time is still 2 September in UTC: it belongs to 3 September.
		('dash-t3', '` + statsAccountA + `', '` + statsAccountA + `', 'Deposit', 10.00, 'SGD', '2031-09-03 00:00:00+08', 'Pending', 'Matched'),
		('dash-t4', '` + statsAccountB + `', '` + statsAccountB + `', 'Deposit', 5.00, 'SGD', '2031-09-30 23:59:59+08', 'Completed', 'Matched'),
		('dash-t5', 'ACC-000123', NULL, 'Deposit', 1.00, 'SGD', '2031-09-15 12:00:00+08', 'Completed', 'Unmatched'),
		-- The first second after the window.
		('dash-t6', '` + statsAccountA + `', '` + statsAccountA + `', 'Deposit', 999.00, 'SGD', '2031-10-01 00:00:00+08', 'Completed', 'Matched'),
		-- Pending for far longer than a day, outside any window.
		('dash-t7', '` + statsAccountA + `', '` + statsAccountA + `', 'Withdrawal', 3.00, 'SGD', '2026-01-01 00:00:00+08', 'Pending', 'Matched'),
		-- Two currencies in November.
		('dash-t8', '` + statsAccountC + `', '` + statsAccountC + `', 'Deposit', 5.00, 'USD', '2031-11-15 12:00:00+08', 'Completed', 'Matched'),
		('dash-t9', '` + statsAccountC + `', '` + statsAccountC + `', 'Deposit', 7.00, 'SGD', '2031-11-20 12:00:00+08', 'Completed', 'Matched');`)
	require.NoError(t, err)
}

func statsQuery(month time.Month, lastDay int) models.TransactionStatsQuery {
	return models.TransactionStatsQuery{
		From: time.Date(2031, month, 1, 0, 0, 0, 0, time.UTC),
		To:   time.Date(2031, month, lastDay, 0, 0, 0, 0, time.UTC),
	}
}

func TestTransactionStatsForAccounts(t *testing.T) {
	db := openTestDB(t)
	loadStatsFixture(t, db)
	query := statsQuery(time.September, 30)
	query.AccountIDs = []string{statsAccountA}

	stats, err := NewTransactionStats(db).TransactionStats(context.Background(), query)

	require.NoError(t, err)
	assert.Equal(t, int64(3), stats.Total)
	assert.Equal(t, map[string]int64{"Completed": 1, "Failed": 1, "Pending": 1}, stats.ByStatus)
	assert.Equal(t, map[string]int64{"Deposit": 2, "Withdrawal": 1}, stats.ByType)
	assert.Equal(t, []string{"SGD"}, stats.Currencies)
	assert.Equal(t, "150.50", stats.TotalValue)
	assert.Equal(t, []models.DailyTransactions{
		{Date: "2031-09-02", Count: 2, Value: "140.50"},
		{Date: "2031-09-03", Count: 1, Value: "10.00"},
	}, stats.Daily)
	assert.Equal(t, int64(1), stats.PendingBacklog)
	assert.Nil(t, stats.Unmatched)
}

func TestTransactionStatsForEveryTransactionCountsUnmatchedOnes(t *testing.T) {
	db := openTestDB(t)
	loadStatsFixture(t, db)
	query := statsQuery(time.September, 30)
	query.All = true

	stats, err := NewTransactionStats(db).TransactionStats(context.Background(), query)

	require.NoError(t, err)
	// t1 to t5; t6 is after the window.
	assert.Equal(t, int64(5), stats.Total)
	assert.Equal(t, "156.50", stats.TotalValue)
	require.NotNil(t, stats.Unmatched)
	assert.Equal(t, int64(1), *stats.Unmatched)
}

func TestTransactionStatsDoesNotAddCurrencies(t *testing.T) {
	db := openTestDB(t)
	loadStatsFixture(t, db)
	query := statsQuery(time.November, 30)
	query.AccountIDs = []string{statsAccountC}

	stats, err := NewTransactionStats(db).TransactionStats(context.Background(), query)

	require.NoError(t, err)
	assert.Equal(t, int64(2), stats.Total)
	assert.Equal(t, []string{"SGD", "USD"}, stats.Currencies)
	assert.Empty(t, stats.TotalValue)
	require.Len(t, stats.Daily, 2)
	for _, day := range stats.Daily {
		assert.Empty(t, day.Value)
	}
}

func TestTransactionStatsForNoAccountsIsEmpty(t *testing.T) {
	query := statsQuery(time.September, 30)
	query.AccountIDs = []string{}

	// No database: an empty account list is answered without a query.
	stats, err := NewTransactionStats(nil).TransactionStats(context.Background(), query)

	require.NoError(t, err)
	assert.Equal(t, int64(0), stats.Total)
	assert.NotNil(t, stats.Daily)
	assert.NotNil(t, stats.Currencies)
}
