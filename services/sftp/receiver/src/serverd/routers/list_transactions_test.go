package routers

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"receiver/src/internal/contract"
	"receiver/src/internal/models"
	"receiver/src/internal/repository"
	"receiver/src/internal/repository/orm"
	"receiver/src/serverd/security"

	"github.com/aarondl/null/v8"
	"github.com/aarondl/sqlboiler/v4/types"
	"github.com/ericlagergren/decimal"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

func serveList(t *testing.T, repo repository.Transaction, query string) *httptest.ResponseRecorder {
	t.Helper()
	return serveListAs(t, repo, nil, admin, query)
}

func serveListAs(t *testing.T, repo repository.Transaction, audit *auditStub, caller *security.Principal, query string) *httptest.ResponseRecorder {
	t.Helper()
	router := newRouter(repo, nil, nil, audit, caller)
	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/api/transactions"+query, nil))
	contract.Check(t, "transactions-api.yaml", http.MethodGet, "/api/transactions", response)
	return response
}

func rows(n int) orm.TransactionSlice {
	start := time.Date(2026, 10, 1, 9, 0, 0, 0, time.UTC)
	slice := orm.TransactionSlice{}
	for i := 0; i < n; i++ {
		amount, _ := new(decimal.Big).SetString("250.00")
		slice = append(slice, &orm.Transaction{
			TransactionID:    "tx-" + string(rune('a'+i)),
			SourceAccountRef: "ACC-1",
			TransactionType:  "Deposit",
			Amount:           types.NewDecimal(amount),
			Currency:         null.StringFrom("SGD"),
			TransactionDate:  start.Add(-time.Duration(i) * time.Hour),
			Status:           "Completed",
			ImportedAt:       start,
			ImportStatus:     "Unmatched",
		})
	}
	return slice
}

func TestListTransactions(t *testing.T) {
	t.Run("asks for one row more than the page and returns no cursor on the last page", func(t *testing.T) {
		repo := repository.NewMockTransaction(t)
		repo.On("ListTransactions", mock.Anything, models.TransactionListQuery{Limit: defaultListSize + 1}).Return(rows(2), nil).Once()

		response := serveList(t, repo, "")

		require.Equal(t, http.StatusOK, response.Code)
		var page struct {
			Content    []map[string]any `json:"content"`
			NextCursor *string          `json:"nextCursor"`
		}
		require.NoError(t, json.Unmarshal(response.Body.Bytes(), &page))
		assert.Len(t, page.Content, 2)
		assert.Nil(t, page.NextCursor)
	})

	t.Run("trims the extra row and returns a cursor that resumes after the last row shown", func(t *testing.T) {
		repo := repository.NewMockTransaction(t)
		all := rows(3)
		repo.On("ListTransactions", mock.Anything, models.TransactionListQuery{Status: "Pending", Limit: 3}).Return(all, nil).Once()

		response := serveList(t, repo, "?size=2&status=Pending")

		require.Equal(t, http.StatusOK, response.Code)
		var page struct {
			Content    []map[string]any `json:"content"`
			NextCursor *string          `json:"nextCursor"`
		}
		require.NoError(t, json.Unmarshal(response.Body.Bytes(), &page))
		assert.Len(t, page.Content, 2)
		require.NotNil(t, page.NextCursor)

		next := repository.NewMockTransaction(t)
		want := models.TransactionCursor{Date: all[1].TransactionDate, ID: all[1].TransactionID}
		next.On("ListTransactions", mock.Anything, mock.MatchedBy(func(query models.TransactionListQuery) bool {
			return query.After != nil && query.After.ID == want.ID && query.After.Date.Equal(want.Date)
		})).Return(orm.TransactionSlice{}, nil).Once()
		assert.Equal(t, http.StatusOK, serveList(t, next, "?cursor="+*page.NextCursor).Code)
	})

	t.Run("returns an internal error when the repository fails", func(t *testing.T) {
		repo := repository.NewMockTransaction(t)
		repo.On("ListTransactions", mock.Anything, mock.Anything).Return(nil, errors.New("database unavailable")).Once()

		response := serveList(t, repo, "")

		assert.Equal(t, http.StatusInternalServerError, response.Code)
		assert.JSONEq(t, `{"error":"could not retrieve transactions"}`, response.Body.String())
	})

	for name, query := range map[string]string{
		"a size that is text":       "?size=many",
		"a zero size":               "?size=0",
		"a size above the maximum":  "?size=101",
		"an unknown status":         "?status=Settled",
		"a cursor that is not ours": "?cursor=not-a-cursor",
	} {
		t.Run("rejects "+name, func(t *testing.T) {
			// The mock fails the test if the repository is called.
			response := serveList(t, repository.NewMockTransaction(t), query)

			assert.Equal(t, http.StatusBadRequest, response.Code)
		})
	}
}

func TestListTransactionsIsForAdminsOnly(t *testing.T) {
	t.Run("an admin's read is audited with no resource ID", func(t *testing.T) {
		repo := repository.NewMockTransaction(t)
		repo.On("ListTransactions", mock.Anything, mock.Anything).Return(orm.TransactionSlice{}, nil).Once()
		audit := &auditStub{}

		response := serveListAs(t, repo, audit, admin, "")

		assert.Equal(t, http.StatusOK, response.Code)
		require.Len(t, audit.payloads(t), 1)
		assert.Equal(t, "TRANSACTION", audit.payloads(t)[0]["resource_type"])
		assert.NotContains(t, audit.payloads(t)[0], "resource_id")
	})

	t.Run("an agent is refused, and the refusal is audited", func(t *testing.T) {
		// The mock fails the test if the repository is called.
		audit := &auditStub{}

		response := serveListAs(t, repository.NewMockTransaction(t), audit, agent, "")

		assert.Equal(t, http.StatusForbidden, response.Code)
		require.Len(t, audit.payloads(t), 1)
		assert.Equal(t, float64(http.StatusForbidden), audit.payloads(t)[0]["status_code"])
	})

	t.Run("a caller with no token is refused, unaudited", func(t *testing.T) {
		audit := &auditStub{}

		response := serveListAs(t, repository.NewMockTransaction(t), audit, nil, "")

		assert.Equal(t, http.StatusUnauthorized, response.Code)
		assert.Empty(t, audit.events)
	})
}
