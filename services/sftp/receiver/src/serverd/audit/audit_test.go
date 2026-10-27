package audit

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"receiver/src/internal/models"
	"receiver/src/serverd/security"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const accountID = "a0000000-0000-0000-0000-000000000001"

type recorder struct {
	events []models.AuditEvent
	err    error
}

func (r *recorder) save(_ context.Context, event models.AuditEvent) error {
	r.events = append(r.events, event)
	return r.err
}

// serve sends one request through Read, as the caller (none if nil), to a
// handler answering status.
func serve(t *testing.T, save Save, caller *security.Principal, route, path string, resourceType, idParam string, status int) *httptest.ResponseRecorder {
	t.Helper()
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(func(c *gin.Context) {
		if caller != nil {
			security.SetCaller(c, *caller, "token")
		}
	})
	router.GET(route, Read(save, resourceType, idParam), func(c *gin.Context) { c.Status(status) })
	res := httptest.NewRecorder()
	router.ServeHTTP(res, httptest.NewRequest(http.MethodGet, path, nil))
	return res
}

type decoded struct {
	EventID       string  `json:"event_id"`
	EventType     string  `json:"event_type"`
	Action        string  `json:"action"`
	SourceService string  `json:"source_service"`
	ActorID       string  `json:"actor_id"`
	Payload       payload `json:"payload"`
}

func decode(t *testing.T, event models.AuditEvent) decoded {
	t.Helper()
	var body decoded
	require.NoError(t, json.Unmarshal(event.Body, &body))
	assert.Equal(t, event.ID, body.EventID)
	return body
}

func TestReadRecordsAnAccountsTransactions(t *testing.T) {
	saved := &recorder{}

	serve(t, saved.save, &security.Principal{Subject: "agent-sub", Role: "AGENT"},
		"/api/accounts/:accountId/transactions", "/api/accounts/"+accountID+"/transactions", "ACCOUNT", "accountId", http.StatusOK)

	require.Len(t, saved.events, 1)
	event := decode(t, saved.events[0])
	assert.NotEmpty(t, event.EventID)
	assert.Equal(t, "CRUD", event.EventType)
	assert.Equal(t, "READ", event.Action)
	assert.Equal(t, "TRANSACTION_SERVICE", event.SourceService)
	assert.Equal(t, "agent-sub", event.ActorID)
	assert.Equal(t, "ACCOUNT", event.Payload.ResourceType)
	assert.Equal(t, accountID, event.Payload.ResourceID)
	assert.Equal(t, "/api/accounts/{accountId}/transactions", event.Payload.Endpoint)
	assert.Equal(t, http.MethodGet, event.Payload.HTTPMethod)
	assert.Equal(t, http.StatusOK, event.Payload.StatusCode)
	assert.Equal(t, "AGENT", event.Payload.UserRole)
	_, err := time.Parse(time.RFC3339Nano, event.Payload.DateTime)
	assert.NoError(t, err)
}

// An agent refused the admin list is recorded with the refusal's status and
// no resource ID.
func TestReadRecordsARefusedList(t *testing.T) {
	saved := &recorder{}

	serve(t, saved.save, &security.Principal{Subject: "agent-sub", Role: "AGENT"},
		"/api/transactions", "/api/transactions", "TRANSACTION", "", http.StatusForbidden)

	require.Len(t, saved.events, 1)
	event := decode(t, saved.events[0])
	assert.Equal(t, "TRANSACTION", event.Payload.ResourceType)
	assert.Empty(t, event.Payload.ResourceID)
	assert.Equal(t, http.StatusForbidden, event.Payload.StatusCode)
	assert.NotContains(t, string(saved.events[0].Body), "resource_id")
}

func TestReadGivesEachEventItsOwnID(t *testing.T) {
	saved := &recorder{}
	admin := &security.Principal{Subject: "admin-sub", Role: "ADMIN"}

	serve(t, saved.save, admin, "/api/transactions", "/api/transactions", "TRANSACTION", "", http.StatusOK)
	serve(t, saved.save, admin, "/api/transactions", "/api/transactions", "TRANSACTION", "", http.StatusOK)

	require.Len(t, saved.events, 2)
	assert.NotEqual(t, saved.events[0].ID, saved.events[1].ID)
}

func TestReadSkipsRequestsWithNoVerifiedCaller(t *testing.T) {
	saved := &recorder{}

	serve(t, saved.save, nil, "/api/transactions", "/api/transactions", "TRANSACTION", "", http.StatusUnauthorized)

	assert.Empty(t, saved.events)
}

func TestReadKeepsTheResponseWhenTheSaveFails(t *testing.T) {
	saved := &recorder{err: errors.New("database unavailable")}

	res := serve(t, saved.save, &security.Principal{Subject: "admin-sub", Role: "ADMIN"},
		"/api/transactions", "/api/transactions", "TRANSACTION", "", http.StatusOK)

	assert.Equal(t, http.StatusOK, res.Code)
	assert.Len(t, saved.events, 1)
}
