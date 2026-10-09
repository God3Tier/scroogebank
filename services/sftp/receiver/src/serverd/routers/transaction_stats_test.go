package routers

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"receiver/src/internal/models"

	"github.com/gin-gonic/gin"
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

func postStats(stats *statsStub, body string) *httptest.ResponseRecorder {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	RegisterRoutes(router, nil, stats)
	res := httptest.NewRecorder()
	router.ServeHTTP(res, httptest.NewRequest(http.MethodPost, "/aggregates/transactions/stats", strings.NewReader(body)))
	return res
}

func TestTransactionStatsForAccounts(t *testing.T) {
	stats := &statsStub{}

	res := postStats(stats, `{"from":"2026-09-01","to":"2026-09-30","accountIds":["A0000000-0000-0000-0000-000000000001"]}`)

	assert.Equal(t, http.StatusOK, res.Code)
	assert.Contains(t, res.Body.String(), `"total":3`)
	require.Len(t, stats.queries, 1)
	assert.Equal(t, models.TransactionStatsQuery{
		From:       time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC),
		To:         time.Date(2026, 9, 30, 0, 0, 0, 0, time.UTC),
		AccountIDs: []string{"a0000000-0000-0000-0000-000000000001"},
	}, stats.queries[0])
}

func TestTransactionStatsForEveryTransaction(t *testing.T) {
	stats := &statsStub{}

	res := postStats(stats, `{"from":"2026-09-01","to":"2026-09-30","all":true}`)

	assert.Equal(t, http.StatusOK, res.Code)
	require.Len(t, stats.queries, 1)
	assert.True(t, stats.queries[0].All)
	assert.Nil(t, stats.queries[0].AccountIDs)
}

func TestTransactionStatsForAnAgentWithNoAccounts(t *testing.T) {
	stats := &statsStub{}

	res := postStats(stats, `{"from":"2026-09-01","to":"2026-09-30","accountIds":[]}`)

	assert.Equal(t, http.StatusOK, res.Code)
	require.Len(t, stats.queries, 1)
	assert.False(t, stats.queries[0].All)
	assert.Empty(t, stats.queries[0].AccountIDs)
}

func TestTransactionStatsRefusesUnclearRequests(t *testing.T) {
	tests := map[string]string{
		"no scope at all":          `{"from":"2026-09-01","to":"2026-09-30"}`,
		"both scopes":              `{"from":"2026-09-01","to":"2026-09-30","all":true,"accountIds":[]}`,
		"account that is no UUID":  `{"from":"2026-09-01","to":"2026-09-30","accountIds":["ACC-000123"]}`,
		"missing window":           `{"all":true}`,
		"window running backwards": `{"from":"2026-09-30","to":"2026-09-01","all":true}`,
		"window over 366 days":     `{"from":"2025-09-29","to":"2026-09-30","all":true}`,
		"not JSON":                 `all`,
	}
	for name, body := range tests {
		t.Run(name, func(t *testing.T) {
			stats := &statsStub{}

			res := postStats(stats, body)

			assert.Equal(t, http.StatusBadRequest, res.Code)
			assert.Empty(t, stats.queries)
		})
	}
}

func TestTransactionStatsHidesDatabaseErrors(t *testing.T) {
	stats := &statsStub{err: errors.New("connection refused")}

	res := postStats(stats, `{"from":"2026-09-01","to":"2026-09-30","all":true}`)

	assert.Equal(t, http.StatusInternalServerError, res.Code)
	assert.NotContains(t, res.Body.String(), "connection refused")
}
