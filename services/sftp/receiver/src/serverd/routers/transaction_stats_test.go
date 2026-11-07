package routers

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"receiver/src/internal/contract"
	"receiver/src/internal/models"
	"receiver/src/serverd/accounts"
	"receiver/src/serverd/security"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type statsStub struct {
	queries []models.TransactionStatsQuery
	err     error
}

func (s *statsStub) TransactionStats(_ context.Context, query models.TransactionStatsQuery) (models.TransactionStats, error) {
	s.queries = append(s.queries, query)
	return models.TransactionStats{Total: 3, ByStatus: map[string]int64{}, ByType: map[string]int64{}, Currencies: []string{"SGD"}, Daily: []models.DailyTransactions{}}, s.err
}

const september = "?from=2026-09-01&to=2026-09-30"

func getStats(t *testing.T, stats *statsStub, directory *directoryStub, caller *security.Principal, query string) *httptest.ResponseRecorder {
	t.Helper()
	audit := &auditStub{}
	router := newRouter(nil, stats, directory, audit, caller)
	res := httptest.NewRecorder()
	router.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/aggregates/transactions/stats"+query, nil))
	contract.Check(t, "dashboard-aggregates.yaml", http.MethodGet, "/aggregates/transactions/stats", res)
	assert.Empty(t, audit.events, "the dashboard audits each view, not this route")
	return res
}

func TestTransactionStatsForAnAdminCoverEveryTransaction(t *testing.T) {
	stats := &statsStub{}
	directory := &directoryStub{}

	res := getStats(t, stats, directory, admin, september)

	assert.Equal(t, http.StatusOK, res.Code)
	assert.Contains(t, res.Body.String(), `"total":3`)
	require.Len(t, stats.queries, 1)
	assert.Equal(t, models.TransactionStatsQuery{
		From: time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC),
		To:   time.Date(2026, 9, 30, 0, 0, 0, 0, time.UTC),
		All:  true,
	}, stats.queries[0])
	assert.Empty(t, directory.tokens, "account-service is not asked for an admin")
}

func TestTransactionStatsForAnAgentCoverOnlyTheirAccounts(t *testing.T) {
	stats := &statsStub{}
	directory := &directoryStub{owned: []string{"A0000000-0000-0000-0000-000000000001"}}

	res := getStats(t, stats, directory, agent, september)

	assert.Equal(t, http.StatusOK, res.Code)
	require.Len(t, stats.queries, 1)
	assert.False(t, stats.queries[0].All)
	assert.Equal(t, []string{accountID}, stats.queries[0].AccountIDs)
	assert.Equal(t, []string{callerToken}, directory.tokens, "account-service is asked with the caller's own token")
}

func TestTransactionStatsForAnAgentWithNoAccounts(t *testing.T) {
	stats := &statsStub{}

	res := getStats(t, stats, &directoryStub{owned: []string{}}, agent, september)

	assert.Equal(t, http.StatusOK, res.Code)
	require.Len(t, stats.queries, 1)
	assert.False(t, stats.queries[0].All)
	assert.NotNil(t, stats.queries[0].AccountIDs)
	assert.Empty(t, stats.queries[0].AccountIDs)
}

// No query parameter widens an agent's scope.
func TestTransactionStatsIgnoreScopeParameters(t *testing.T) {
	stats := &statsStub{}

	res := getStats(t, stats, &directoryStub{owned: []string{}}, agent, september+"&all=true&accountIds="+accountID)

	assert.Equal(t, http.StatusOK, res.Code)
	require.Len(t, stats.queries, 1)
	assert.False(t, stats.queries[0].All)
	assert.Empty(t, stats.queries[0].AccountIDs)
}

func TestTransactionStatsWhenAccountServiceRefusesOrFails(t *testing.T) {
	tests := map[string]struct {
		directory  *directoryStub
		wantStatus int
	}{
		"account-service refuses the caller": {directory: &directoryStub{ownedErr: accounts.Refused{Status: http.StatusForbidden}}, wantStatus: http.StatusForbidden},
		"account-service cannot answer":      {directory: &directoryStub{ownedErr: accounts.ErrUnavailable}, wantStatus: http.StatusBadGateway},
		"an account ID that is no UUID":      {directory: &directoryStub{owned: []string{"ACC-000123"}}, wantStatus: http.StatusBadGateway},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			stats := &statsStub{}

			res := getStats(t, stats, test.directory, agent, september)

			assert.Equal(t, test.wantStatus, res.Code)
			assert.Empty(t, stats.queries)
		})
	}
}

func TestTransactionStatsRefuseABadWindow(t *testing.T) {
	tests := map[string]string{
		"missing window":           "",
		"missing end":              "?from=2026-09-01",
		"a date that is no date":   "?from=2026-09-01&to=September",
		"window running backwards": "?from=2026-09-30&to=2026-09-01",
		"window over 366 days":     "?from=2025-09-29&to=2026-09-30",
	}
	for name, query := range tests {
		t.Run(name, func(t *testing.T) {
			stats := &statsStub{}
			directory := &directoryStub{}

			res := getStats(t, stats, directory, agent, query)

			assert.Equal(t, http.StatusBadRequest, res.Code)
			assert.Empty(t, stats.queries)
			assert.Empty(t, directory.tokens, "account-service is not asked about a bad request")
		})
	}
}

func TestTransactionStatsRefuseACallerWithNoToken(t *testing.T) {
	stats := &statsStub{}

	res := getStats(t, stats, &directoryStub{}, nil, september)

	assert.Equal(t, http.StatusUnauthorized, res.Code)
	assert.Empty(t, stats.queries)
}

func TestTransactionStatsHideDatabaseErrors(t *testing.T) {
	stats := &statsStub{err: errors.New("connection refused")}

	res := getStats(t, stats, &directoryStub{}, admin, september)

	assert.Equal(t, http.StatusInternalServerError, res.Code)
	assert.NotContains(t, res.Body.String(), "connection refused")
}
