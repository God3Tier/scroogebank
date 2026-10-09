package sqs

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type savedEvent struct {
	id   string
	body []byte
}

type fakeOutbox struct {
	saved []savedEvent
	calls int
	err   error
	// ctxErr is the context's error when Save was last called.
	ctxErr error
}

func (f *fakeOutbox) Save(ctx context.Context, eventID string, body []byte) error {
	f.calls++
	f.ctxErr = ctx.Err()
	if f.err != nil {
		return f.err
	}
	f.saved = append(f.saved, savedEvent{id: eventID, body: body})
	return nil
}

func setupAuditRouter(outbox Outbox, route string, method string, handler gin.HandlerFunc) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(func(c *gin.Context) {
		c.Set("agent_id", "verified-agent-subject")
		c.Next()
	})
	router.Handle(method, route, AuditLogger(outbox), handler)
	return router
}

func decodeSavedEvent(t *testing.T, saved savedEvent) (Event, EndpointPayload) {
	t.Helper()
	var event Event
	require.NoError(t, json.Unmarshal(saved.body, &event))
	var payload EndpointPayload
	require.NoError(t, json.Unmarshal(event.Payload, &payload))
	return event, payload
}

func TestAuditLogger_SavesEventAfterEndpointCompletes(t *testing.T) {
	outbox := &fakeOutbox{}
	bodySeenByHandler := ""
	router := setupAuditRouter(outbox, "/api/clients/:clientId", http.MethodPut, func(c *gin.Context) {
		body, err := io.ReadAll(c.Request.Body)
		require.NoError(t, err)
		bodySeenByHandler = string(body)
		c.Status(http.StatusOK)
	})

	requestBody := `{"email":"private@example.test"}`
	request := httptest.NewRequest(http.MethodPut, "/api/clients/client-123", strings.NewReader(requestBody))
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)

	require.Equal(t, http.StatusOK, response.Code)
	require.Equal(t, requestBody, bodySeenByHandler, "audit middleware must restore the body for the endpoint")
	require.Len(t, outbox.saved, 1, "one completed endpoint call should save one audit event")

	event, payload := decodeSavedEvent(t, outbox.saved[0])
	assert.Equal(t, event.EventID, outbox.saved[0].id, "the outbox row is keyed by the event's own ID")
	assert.NotEmpty(t, event.EventID)
	assert.Equal(t, eventType, event.EventType)
	assert.Equal(t, "UPDATE", event.Action)
	assert.Equal(t, sourceService, event.SourceService)
	assert.Equal(t, "verified-agent-subject", event.ActorID)
	assert.Equal(t, "verified-agent-subject", payload.AgentID)
	assert.Equal(t, "client-123", payload.ClientID)
	assert.Equal(t, "/api/clients/:clientId", payload.Endpoint)
	assert.Equal(t, http.MethodPut, payload.HTTPMethod)
	assert.Equal(t, http.StatusOK, payload.StatusCode)
	assert.NotEmpty(t, payload.DateTime)
	assert.NotContains(t, string(outbox.saved[0].body), "private@example.test", "request PII must not reach the Auditor")
}

func TestAuditLogger_ExtractsClientIDFromCreateRequest(t *testing.T) {
	outbox := &fakeOutbox{}
	router := setupAuditRouter(outbox, "/api/accounts", http.MethodPost, func(c *gin.Context) {
		body, err := io.ReadAll(c.Request.Body)
		require.NoError(t, err)
		assert.Contains(t, string(body), `"client_id":"client-from-body"`)
		c.Status(http.StatusCreated)
	})

	request := httptest.NewRequest(http.MethodPost, "/api/accounts", strings.NewReader(`{"client_id":"client-from-body"}`))
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)

	require.Equal(t, http.StatusCreated, response.Code)
	require.Len(t, outbox.saved, 1)
	event, payload := decodeSavedEvent(t, outbox.saved[0])
	assert.Equal(t, "CREATE", event.Action)
	assert.Equal(t, "client-from-body", payload.ClientID)
}

func TestAuditLogger_DoesNotSaveWithoutAuthenticatedAgentID(t *testing.T) {
	gin.SetMode(gin.TestMode)
	outbox := &fakeOutbox{}
	router := gin.New()
	router.PUT("/api/clients/:clientId", AuditLogger(outbox), func(c *gin.Context) {
		c.Status(http.StatusNoContent)
	})

	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest(http.MethodPut, "/api/clients/client-123", nil))

	assert.Equal(t, http.StatusNoContent, response.Code)
	assert.Zero(t, outbox.calls, "an event without a verified actor must not be saved")
}

func TestAuditLogger_SaveFailureDoesNotChangeCompletedResponse(t *testing.T) {
	outbox := &fakeOutbox{err: errors.New("database unavailable")}
	router := setupAuditRouter(outbox, "/api/clients/:clientId", http.MethodDelete, func(c *gin.Context) {
		c.Status(http.StatusNoContent)
	})

	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest(http.MethodDelete, "/api/clients/client-123", nil))

	assert.Equal(t, http.StatusNoContent, response.Code)
	assert.Equal(t, 1, outbox.calls)
	assert.Empty(t, outbox.saved)
}

func TestAuditLogger_SavesEvenIfTheCallerHasGoneAway(t *testing.T) {
	outbox := &fakeOutbox{}
	ctx, cancel := context.WithCancel(context.Background())
	router := setupAuditRouter(outbox, "/api/clients/:clientId", http.MethodDelete, func(c *gin.Context) {
		c.Status(http.StatusNoContent)
		cancel() // the caller disconnects after the change has committed
	})

	request := httptest.NewRequest(http.MethodDelete, "/api/clients/client-123", nil).WithContext(ctx)
	router.ServeHTTP(httptest.NewRecorder(), request)

	require.Len(t, outbox.saved, 1)
	assert.NoError(t, outbox.ctxErr, "the save must not inherit the request's cancellation")
}

func TestActionForRequest(t *testing.T) {
	tests := []struct {
		name   string
		method string
		route  string
		want   string
		ok     bool
	}{
		{name: "create", method: http.MethodPost, route: "/api/clients", want: "CREATE", ok: true},
		{name: "get", method: http.MethodGet, route: "/api/clients/:clientId", want: "GET", ok: true},
		{name: "update", method: http.MethodPut, route: "/api/clients/:clientId", want: "UPDATE", ok: true},
		{name: "delete", method: http.MethodDelete, route: "/api/clients/:clientId", want: "DELETE", ok: true},
		{name: "verify", method: http.MethodPost, route: "/api/clients/:clientId/verify", want: "UPDATE", ok: true},
		{name: "unsupported method", method: http.MethodOptions, route: "/api/clients", ok: false},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, ok := actionForRequest(test.method, test.route)
			assert.Equal(t, test.ok, ok)
			assert.Equal(t, test.want, got)
		})
	}
}
