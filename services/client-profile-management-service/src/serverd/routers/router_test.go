package routers

import (
	"net/http"
	"net/http/httptest"
	"testing"

	clientcontroller "client-profile-management-service/src/internal/controller/client"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/security"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
)

// recordingAggregates stands in for the aggregate controller and records the
// caller each route was given.
type recordingAggregates struct {
	callers []models.Caller
}

func (r *recordingAggregates) ClientStats(w http.ResponseWriter, _ *http.Request, caller models.Caller) {
	r.callers = append(r.callers, caller)
	w.WriteHeader(http.StatusOK)
}

func (r *recordingAggregates) ActionItems(w http.ResponseWriter, _ *http.Request, caller models.Caller) {
	r.callers = append(r.callers, caller)
	w.WriteHeader(http.StatusOK)
}

func (r *recordingAggregates) TeamComparison(w http.ResponseWriter, _ *http.Request, caller models.Caller) {
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
	noAudit := func(c *gin.Context) { c.Next() }
	RegisterRoutes(router, clientcontroller.New(nil), aggregates, nil, signedInAs(principal), noAudit)
	res := httptest.NewRecorder()
	router.ServeHTTP(res, httptest.NewRequest(http.MethodGet, path, nil))
	return res, aggregates
}

func TestAggregatesTakeTheCallerFromTheTokenOnly(t *testing.T) {
	agent := &security.Principal{Subject: "agent-a", Role: "AGENT"}

	// Neither parameter exists in the API; they must change nothing.
	res, aggregates := serveAggregates(agent, "/aggregates/clients/stats?from=2026-09-01&to=2026-09-30&agentId=agent-b&admin=true")

	assert.Equal(t, http.StatusOK, res.Code)
	assert.Equal(t, []models.Caller{{Subject: "agent-a"}}, aggregates.callers)
}

func TestTeamComparisonTakesTheCallerFromTheTokenOnly(t *testing.T) {
	agent := &security.Principal{Subject: "agent-a", Role: "AGENT"}

	// Asking for another agent's figure changes nothing.
	res, aggregates := serveAggregates(agent, "/aggregates/clients/team-comparison?days=30&agentId=agent-b")

	assert.Equal(t, http.StatusOK, res.Code)
	assert.Equal(t, []models.Caller{{Subject: "agent-a"}}, aggregates.callers)
}

func TestAggregatesMarkAdmins(t *testing.T) {
	admin := &security.Principal{Subject: "admin-1", Role: "ADMIN"}

	res, aggregates := serveAggregates(admin, "/aggregates/clients/action-items")

	assert.Equal(t, http.StatusOK, res.Code)
	assert.Equal(t, []models.Caller{{Subject: "admin-1", Admin: true}}, aggregates.callers)
}

func TestAggregatesRefuseCallersWithoutACRMRole(t *testing.T) {
	tests := map[string]struct {
		principal  *security.Principal
		wantStatus int
	}{
		"no token":    {wantStatus: http.StatusUnauthorized},
		"no CRM role": {principal: &security.Principal{Subject: "someone", Role: ""}, wantStatus: http.StatusForbidden},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			res, aggregates := serveAggregates(test.principal, "/aggregates/clients/stats")

			assert.Equal(t, test.wantStatus, res.Code)
			assert.Empty(t, aggregates.callers)
		})
	}
}
