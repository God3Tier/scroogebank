package routers

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"account-service/src/internal/contract"
	accountcontroller "account-service/src/internal/controller/account"
	"account-service/src/internal/models"
	"account-service/src/internal/security"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
)

// recordingAggregates stands in for the aggregate controller and records the
// caller each route was given.
type recordingAggregates struct {
	callers []models.Caller
}

func (r *recordingAggregates) AccountStats(w http.ResponseWriter, _ *http.Request, caller models.Caller) {
	r.callers = append(r.callers, caller)
	w.WriteHeader(http.StatusOK)
}

func (r *recordingAggregates) OwnedAccountIDs(w http.ResponseWriter, _ *http.Request, caller models.Caller) {
	r.callers = append(r.callers, caller)
	w.WriteHeader(http.StatusOK)
}

// signedInAs replaces Cognito verification with a fixed principal, or with a
// 401 when there is none.
func signedInAs(principal *security.Principal) gin.HandlerFunc {
	return func(c *gin.Context) {
		if principal == nil {
			c.AbortWithStatus(http.StatusUnauthorized)
			return
		}
		c.Set("principal", *principal)
		c.Next()
	}
}

func serveAggregates(principal *security.Principal, path string) (*httptest.ResponseRecorder, *recordingAggregates) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	aggregates := &recordingAggregates{}
	pass := func(c *gin.Context) { c.Next() }
	RegisterRoutes(router, accountcontroller.NewAccountController(nil), aggregates, nil, signedInAs(principal), pass, pass)
	res := httptest.NewRecorder()
	router.ServeHTTP(res, httptest.NewRequest(http.MethodGet, path, nil))
	return res, aggregates
}

func TestAggregatesTakeTheCallerFromTheTokenOnly(t *testing.T) {
	agent := &security.Principal{Subject: "agent-a", Role: "AGENT"}

	// Neither parameter exists in the API; they must change nothing.
	res, aggregates := serveAggregates(agent, "/aggregates/accounts/ids?ownerAgentId=agent-b&admin=true")

	assert.Equal(t, http.StatusOK, res.Code)
	assert.Equal(t, []models.Caller{{Subject: "agent-a"}}, aggregates.callers)
}

func TestAggregatesMarkAdmins(t *testing.T) {
	admin := &security.Principal{Subject: "admin-1", Role: "ADMIN"}

	res, aggregates := serveAggregates(admin, "/aggregates/accounts/stats")

	assert.Equal(t, http.StatusOK, res.Code)
	assert.Equal(t, []models.Caller{{Subject: "admin-1", Admin: true}}, aggregates.callers)
}

func TestAggregatesRefuseCallersWithoutACRMRole(t *testing.T) {
	tests := map[string]struct {
		principal  *security.Principal
		wantStatus int
	}{
		"no token":    {wantStatus: http.StatusUnauthorized},
		"no CRM role": {principal: &security.Principal{Subject: "transaction-ingest"}, wantStatus: http.StatusForbidden},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			res, aggregates := serveAggregates(test.principal, "/aggregates/accounts/stats")

			assert.Equal(t, test.wantStatus, res.Code)
			assert.Empty(t, aggregates.callers)
		})
	}
}

func TestWithCallerAndAccountPassesTheTokenCallerAndRouteAccount(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	var gotCaller models.Caller
	var gotAccountID string
	router.PATCH("/api/accounts/:accountId", signedInAs(&security.Principal{Subject: "agent-a", Role: "AGENT"}),
		withCallerAndAccount(func(w http.ResponseWriter, _ *http.Request, caller models.Caller, accountID string) {
			gotCaller, gotAccountID = caller, accountID
			w.WriteHeader(http.StatusOK)
		}))
	res := httptest.NewRecorder()

	router.ServeHTTP(res, httptest.NewRequest(http.MethodPatch, "/api/accounts/account-1?admin=true", nil))

	assert.Equal(t, http.StatusOK, res.Code)
	assert.Equal(t, models.Caller{Subject: "agent-a"}, gotCaller)
	assert.Equal(t, "account-1", gotAccountID)
}

// Admins only read accounts (ADR-0059). The refusal comes before the
// ownership check and the controller, so neither a database nor a service is
// needed here.
func TestAccountWritesRefuseAdmins(t *testing.T) {
	gin.SetMode(gin.TestMode)
	admin := &security.Principal{Subject: "admin-1", Role: "ADMIN"}
	const accountPath = "/api/accounts/00000000-0000-0000-0000-000000000301"
	requests := map[string]struct{ method, path string }{
		"open":          {http.MethodPost, "/api/accounts"},
		"delete":        {http.MethodDelete, accountPath},
		"change status": {http.MethodPatch, accountPath},
	}
	for name, request := range requests {
		t.Run(name, func(t *testing.T) {
			router := gin.New()
			pass := func(c *gin.Context) { c.Next() }
			RegisterRoutes(router, accountcontroller.NewAccountController(nil), &recordingAggregates{}, nil, signedInAs(admin), pass, pass)
			res := httptest.NewRecorder()

			router.ServeHTTP(res, httptest.NewRequest(request.method, request.path, nil))

			assert.Equal(t, http.StatusForbidden, res.Code)
		})
	}
}

// Only admins move a reassigned client's accounts (ADR-0060). A malformed
// body shows the request reached the controller without needing a service.
func TestOwnershipIsForAdminsOnly(t *testing.T) {
	gin.SetMode(gin.TestMode)
	tests := map[string]struct {
		principal  *security.Principal
		wantStatus int
	}{
		"an admin reaches the controller": {principal: &security.Principal{Subject: "admin-1", Role: "ADMIN"}, wantStatus: http.StatusBadRequest},
		"an agent is refused":             {principal: &security.Principal{Subject: "agent-a", Role: "AGENT"}, wantStatus: http.StatusForbidden},
		"no token":                        {wantStatus: http.StatusUnauthorized},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			router := gin.New()
			pass := func(c *gin.Context) { c.Next() }
			RegisterRoutes(router, accountcontroller.NewAccountController(nil), &recordingAggregates{}, nil, signedInAs(test.principal), pass, pass)
			res := httptest.NewRecorder()

			router.ServeHTTP(res, httptest.NewRequest(http.MethodPut, "/ownership/accounts", strings.NewReader(`{`)))

			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}

// Every route is in an OpenAPI contract (ADR-0063), including routes no test
// calls.
func TestEveryRouteIsInAContract(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	pass := func(c *gin.Context) { c.Next() }
	RegisterRoutes(router, accountcontroller.NewAccountController(nil), &recordingAggregates{}, nil, pass, pass, pass)

	contract.CheckRoutes(t, router.Routes(), "accounts-api.yaml", "dashboard-aggregates.yaml")
}
