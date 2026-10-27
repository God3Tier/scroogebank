package routers

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"receiver/src/internal/contract"
	"receiver/src/internal/models"
	"receiver/src/internal/repository"
	"receiver/src/internal/repository/orm"
	"receiver/src/serverd/accounts"
	"receiver/src/serverd/security"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	accountID   = "a0000000-0000-0000-0000-000000000001"
	callerToken = "caller-token"
)

var (
	agent = &security.Principal{Subject: "agent-sub", Role: "AGENT"}
	admin = &security.Principal{Subject: "admin-sub", Role: "ADMIN"}
)

type transactionRepositoryStub struct {
	transactions  orm.TransactionSlice
	healthRows    []models.ClientHealthTransaction
	healthQueries []models.ClientHealthTransactionQuery
	err           error
	accountID     string
}

func (s *transactionRepositoryStub) SaveTransaction(context.Context, models.Transaction) error {
	return nil
}

func (s *transactionRepositoryStub) GetTransactionsByAccountID(_ context.Context, accountID string) (orm.TransactionSlice, error) {
	s.accountID = accountID
	return s.transactions, s.err
}

func (s *transactionRepositoryStub) ListTransactions(context.Context, models.TransactionListQuery) (orm.TransactionSlice, error) {
	return s.transactions, s.err
}

// directoryStub stands in for account-service and records what it was asked.
type directoryStub struct {
	accessErr error
	owned     []string
	ownedErr  error
	checked   []string
	tokens    []string
}

func (d *directoryStub) CheckAccess(_ context.Context, accountID, token string) error {
	d.checked = append(d.checked, accountID)
	d.tokens = append(d.tokens, token)
	return d.accessErr
}

func (d *directoryStub) OwnedAccountIDs(_ context.Context, token string) ([]string, error) {
	d.tokens = append(d.tokens, token)
	return d.owned, d.ownedErr
}

// auditStub records the events the routes save.
type auditStub struct {
	events []models.AuditEvent
}

func (a *auditStub) save(_ context.Context, event models.AuditEvent) error {
	a.events = append(a.events, event)
	return nil
}

func (s *transactionRepositoryStub) ListClientHealthTransactions(_ context.Context, query models.ClientHealthTransactionQuery) ([]models.ClientHealthTransaction, error) {
	s.healthQueries = append(s.healthQueries, query)
	if s.err != nil {
		return nil, s.err
	}
	rows := make([]models.ClientHealthTransaction, 0, len(s.healthRows))
	for _, row := range s.healthRows {
		if query.After == nil || row.TransactionDate.Before(query.After.Date) ||
			(row.TransactionDate.Equal(query.After.Date) && row.TransactionID < query.After.ID) {
			rows = append(rows, row)
		}
	}
	if len(rows) > query.Limit {
		rows = rows[:query.Limit]
	}
	return rows, nil
}

// payloads returns each saved event's resource type, ID and status.
func (a *auditStub) payloads(t *testing.T) []map[string]any {
	t.Helper()
	var payloads []map[string]any
	for _, event := range a.events {
		var body struct {
			Payload map[string]any `json:"payload"`
		}
		require.NoError(t, json.Unmarshal(event.Body, &body))
		payloads = append(payloads, body.Payload)
	}
	return payloads
}

// newRouter serves the routes to caller, as if the verifier had admitted
// them with callerToken; a nil caller is refused as having no token.
func newRouter(transactions repository.Transaction, stats repository.TransactionStats, directory accounts.Directory,
	audit *auditStub, caller *security.Principal) *gin.Engine {
	gin.SetMode(gin.TestMode)
	authenticate := func(c *gin.Context) {
		if caller == nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Bearer access token required"})
			return
		}
		security.SetCaller(c, *caller, callerToken)
		c.Next()
	}
	if audit == nil {
		audit = &auditStub{}
	}
	router := gin.New()
	RegisterRoutes(router, transactions, stats, directory, authenticate, audit.save)
	RegisterClientHealthRoutes(router, transactions, clientHealthTestAuth())
	return router
}

func clientHealthTestAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		parts := strings.Fields(c.GetHeader("Authorization"))
		if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Bearer service token required"})
			return
		}
		if parts[1] != "valid-health-token" {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "transaction read scope required"})
			return
		}
		c.Next()
	}
}

func getAccountTransactions(t *testing.T, router *gin.Engine, accountID string) *httptest.ResponseRecorder {
	t.Helper()
	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/api/accounts/"+accountID+"/transactions", nil))
	contract.Check(t, "transactions-api.yaml", http.MethodGet, "/api/accounts/{accountId}/transactions", response)
	return response
}

func getClientHealthTransactions(t *testing.T, router *gin.Engine, query string, token string) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(http.MethodGet, "/internal/v1/transactions?"+query, nil)
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)
	contract.Check(t, "transactions-api.yaml", http.MethodGet, "/internal/v1/transactions", response)
	return response
}

func TestListClientHealthTransactions(t *testing.T) {
	from := time.Date(2026, 4, 1, 0, 0, 0, 0, time.UTC)
	to := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	rows := []models.ClientHealthTransaction{
		{TransactionID: "txn-2", ClientID: "fb2c22ef-69fc-47de-b67f-5c1d0cfc795a", AccountID: "e3351530-f5c1-4f64-aedc-3c97d9f76816", TransactionType: "Deposit", Amount: "4200.00", Currency: "SGD", TransactionDate: to.Add(-time.Hour), Status: "Completed", ActivityKind: "Automated"},
		{TransactionID: "txn-1", ClientID: "3e167e0f-79ab-4c35-bd48-57f7c6d80e1d", AccountID: "2f57d589-a614-4da4-8f0b-bd76a218de47", TransactionType: "Withdrawal", Amount: "12.50", Currency: "USD", TransactionDate: to.Add(-2 * time.Hour), Status: "Completed", ActivityKind: "CustomerInitiated"},
	}
	repo := &transactionRepositoryStub{healthRows: rows}
	router := newRouter(repo, nil, nil, nil, nil)
	query := "from=" + from.Format(time.RFC3339) + "&to=" + to.Format(time.RFC3339) + "&limit=1"

	first := getClientHealthTransactions(t, router, query, "valid-health-token")
	require.Equal(t, http.StatusOK, first.Code, first.Body.String())
	var page clientHealthTransactionPage
	require.NoError(t, json.Unmarshal(first.Body.Bytes(), &page))
	require.Len(t, page.Items, 1)
	assert.Equal(t, "fb2c22ef-69fc-47de-b67f-5c1d0cfc795a", page.Items[0].ClientID)
	assert.Equal(t, "Automated", page.Items[0].ActivityKind)
	assert.NotNil(t, page.NextCursor)
	assert.NotContains(t, first.Body.String(), "description")
	assert.NotContains(t, first.Body.String(), "importStatus")
	require.Len(t, repo.healthQueries, 1)
	assert.Equal(t, from, repo.healthQueries[0].From)
	assert.Equal(t, to, repo.healthQueries[0].To)
	assert.Equal(t, 2, repo.healthQueries[0].Limit, "fetch one extra row to determine the next cursor")

	secondQuery := query + "&cursor=" + url.QueryEscape(*page.NextCursor)
	second := getClientHealthTransactions(t, router, secondQuery, "valid-health-token")
	require.Equal(t, http.StatusOK, second.Code, second.Body.String())
	require.NoError(t, json.Unmarshal(second.Body.Bytes(), &page))
	require.Len(t, page.Items, 1)
	assert.Equal(t, "3e167e0f-79ab-4c35-bd48-57f7c6d80e1d", page.Items[0].ClientID)
	assert.Nil(t, page.NextCursor)
	require.Len(t, repo.healthQueries, 2)
	assert.NotNil(t, repo.healthQueries[1].After)
	assert.Equal(t, "txn-2", repo.healthQueries[1].After.ID)
}

func TestListClientHealthTransactionsAuthentication(t *testing.T) {
	for _, test := range []struct {
		name  string
		token string
		want  int
	}{
		{name: "missing token", want: http.StatusUnauthorized},
		{name: "invalid scope or client", token: "invalid-health-token", want: http.StatusForbidden},
	} {
		t.Run(test.name, func(t *testing.T) {
			repo := &transactionRepositoryStub{}
			response := getClientHealthTransactions(t, newRouter(repo, nil, nil, nil, nil), "from=2026-04-01T00:00:00Z&to=2026-10-01T00:00:00Z", test.token)
			assert.Equal(t, test.want, response.Code)
			assert.Empty(t, repo.healthQueries, "unauthenticated requests must not reach the repository")
		})
	}
}

func TestListClientHealthTransactionsRejectsInvalidRange(t *testing.T) {
	repo := &transactionRepositoryStub{}
	response := getClientHealthTransactions(t, newRouter(repo, nil, nil, nil, nil), "from=2026-10-01T00:00:00Z&to=2026-04-01T00:00:00Z", "valid-health-token")
	assert.Equal(t, http.StatusBadRequest, response.Code)
	assert.Empty(t, repo.healthQueries)
}

func TestGetTransactions(t *testing.T) {
	t.Run("returns an account's transactions once account-service admits the caller", func(t *testing.T) {
		repo := &transactionRepositoryStub{transactions: orm.TransactionSlice{}}
		directory := &directoryStub{}
		audit := &auditStub{}

		response := getAccountTransactions(t, newRouter(repo, nil, directory, audit, agent), "A0000000-0000-0000-0000-000000000001")

		require.Equal(t, http.StatusOK, response.Code, response.Body.String())
		assert.Equal(t, "[]", response.Body.String())
		assert.Equal(t, []string{accountID}, directory.checked, "account-service is asked about the account")
		assert.Equal(t, []string{callerToken}, directory.tokens, "with the caller's own token")
		assert.Equal(t, accountID, repo.accountID)
		require.Len(t, audit.payloads(t), 1)
		assert.Equal(t, "ACCOUNT", audit.payloads(t)[0]["resource_type"])
		assert.Equal(t, float64(http.StatusOK), audit.payloads(t)[0]["status_code"])
	})

	t.Run("gives another agent's account the same 404 as account-service", func(t *testing.T) {
		repo := &transactionRepositoryStub{}
		audit := &auditStub{}

		response := getAccountTransactions(t, newRouter(repo, nil, &directoryStub{accessErr: accounts.Refused{Status: http.StatusNotFound}}, audit, agent), accountID)

		assert.Equal(t, http.StatusNotFound, response.Code)
		assert.JSONEq(t, `{"error":"resource not found"}`, response.Body.String())
		assert.Empty(t, repo.accountID, "no transactions are read")
		require.Len(t, audit.payloads(t), 1)
		assert.Equal(t, float64(http.StatusNotFound), audit.payloads(t)[0]["status_code"])
	})

	t.Run("refuses the read when account-service cannot answer", func(t *testing.T) {
		repo := &transactionRepositoryStub{}

		response := getAccountTransactions(t, newRouter(repo, nil, &directoryStub{accessErr: accounts.ErrUnavailable}, nil, agent), accountID)

		assert.Equal(t, http.StatusBadGateway, response.Code)
		assert.Empty(t, repo.accountID)
	})

	t.Run("rejects an account ID that is no UUID without asking account-service", func(t *testing.T) {
		directory := &directoryStub{}

		response := getAccountTransactions(t, newRouter(&transactionRepositoryStub{}, nil, directory, nil, agent), "ACC-000123")

		assert.Equal(t, http.StatusBadRequest, response.Code)
		assert.Empty(t, directory.checked)
	})

	t.Run("returns an internal error when the repository fails", func(t *testing.T) {
		repo := &transactionRepositoryStub{err: errors.New("database unavailable")}

		response := getAccountTransactions(t, newRouter(repo, nil, &directoryStub{}, nil, admin), accountID)

		assert.Equal(t, http.StatusInternalServerError, response.Code)
		assert.JSONEq(t, `{"error":"could not retrieve transactions"}`, response.Body.String())
	})

	t.Run("refuses a caller with no token, unaudited", func(t *testing.T) {
		directory := &directoryStub{}
		audit := &auditStub{}

		response := getAccountTransactions(t, newRouter(&transactionRepositoryStub{}, nil, directory, audit, nil), accountID)

		assert.Equal(t, http.StatusUnauthorized, response.Code)
		assert.Empty(t, directory.checked)
		assert.Empty(t, audit.events)
	})
}

// Every route is in an OpenAPI contract (ADR-0063), including routes no test
// calls.
func TestEveryRouteIsInAContract(t *testing.T) {
	router := newRouter(nil, nil, nil, nil, nil)

	contract.CheckRoutes(t, router.Routes(), "transactions-api.yaml", "dashboard-aggregates.yaml")
}
