package routers

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

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
	transactions orm.TransactionSlice
	err          error
	accountID    string
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
	return router
}

func getAccountTransactions(t *testing.T, router *gin.Engine, accountID string) *httptest.ResponseRecorder {
	t.Helper()
	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/api/accounts/"+accountID+"/transactions", nil))
	contract.Check(t, "transactions-api.yaml", http.MethodGet, "/api/accounts/{accountId}/transactions", response)
	return response
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
