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

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeSQS struct {
	inputs []*sqs.SendMessageInput
	calls  int
	err    error
}

func (f *fakeSQS) SendMessage(_ context.Context, input *sqs.SendMessageInput, _ ...func(*sqs.Options)) (*sqs.SendMessageOutput, error) {
	f.calls++
	if f.err != nil {
		return nil, f.err
	}
	f.inputs = append(f.inputs, input)
	return &sqs.SendMessageOutput{}, nil
}

func setupAuditRouter(client SQSAPI, route string, method string, handler gin.HandlerFunc) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(func(c *gin.Context) {
		c.Set("agent_id", "verified-agent-subject")
		c.Next()
	})
	router.Handle(method, route, AuditLogger(client, "audit-queue-url"), handler)
	return router
}

func decodeSentEvent(t *testing.T, input *sqs.SendMessageInput) (Event, EndpointPayload) {
	t.Helper()
	var event Event
	require.NoError(t, json.Unmarshal([]byte(aws.ToString(input.MessageBody)), &event))
	var payload EndpointPayload
	require.NoError(t, json.Unmarshal(event.Payload, &payload))
	return event, payload
}

func TestAuditLogger_SendsEventAfterEndpointCompletes(t *testing.T) {
	sqsClient := &fakeSQS{}
	bodySeenByHandler := ""
	router := setupAuditRouter(sqsClient, "/api/clients/:clientId", http.MethodPut, func(c *gin.Context) {
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
	require.Len(t, sqsClient.inputs, 1, "one completed endpoint call should send one SQS event")

	input := sqsClient.inputs[0]
	assert.Equal(t, "audit-queue-url", aws.ToString(input.QueueUrl))
	event, payload := decodeSentEvent(t, input)
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
	assert.NotContains(t, aws.ToString(input.MessageBody), "private@example.test", "request PII must not be sent to the Auditor")
}

func TestAuditLogger_ExtractsClientIDFromCreateRequest(t *testing.T) {
	sqsClient := &fakeSQS{}
	router := setupAuditRouter(sqsClient, "/api/accounts", http.MethodPost, func(c *gin.Context) {
		body, err := io.ReadAll(c.Request.Body)
		require.NoError(t, err)
		assert.Contains(t, string(body), `"client_id":"client-from-body"`)
		c.Status(http.StatusCreated)
	})

	request := httptest.NewRequest(http.MethodPost, "/api/accounts", strings.NewReader(`{"client_id":"client-from-body"}`))
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)

	require.Equal(t, http.StatusCreated, response.Code)
	require.Len(t, sqsClient.inputs, 1)
	event, payload := decodeSentEvent(t, sqsClient.inputs[0])
	assert.Equal(t, "CREATE", event.Action)
	assert.Equal(t, "client-from-body", payload.ClientID)
}

func TestAuditLogger_ExtractsClientIDFromListQuery(t *testing.T) {
	sqsClient := &fakeSQS{}
	router := setupAuditRouter(sqsClient, "/api/accounts", http.MethodGet, func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	request := httptest.NewRequest(http.MethodGet, "/api/accounts?clientId=client-from-query", nil)
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)

	require.Equal(t, http.StatusOK, response.Code)
	require.Len(t, sqsClient.inputs, 1)
	event, payload := decodeSentEvent(t, sqsClient.inputs[0])
	assert.Equal(t, "GET", event.Action)
	assert.Equal(t, "client-from-query", payload.ClientID)
}

func TestAuditLogger_DoesNotSendWithoutAuthenticatedAgentID(t *testing.T) {
	gin.SetMode(gin.TestMode)
	sqsClient := &fakeSQS{}
	router := gin.New()
	router.PUT("/api/clients/:clientId", AuditLogger(sqsClient, "audit-queue-url"), func(c *gin.Context) {
		c.Status(http.StatusNoContent)
	})

	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest(http.MethodPut, "/api/clients/client-123", nil))

	assert.Equal(t, http.StatusNoContent, response.Code)
	assert.Equal(t, 1, sqsClient.calls, "middleware should attempt to send the audit event")
	assert.Empty(t, sqsClient.inputs)
}

func TestAuditLogger_SendFailureDoesNotChangeCompletedResponse(t *testing.T) {
	gin.SetMode(gin.TestMode)
	sqsClient := &fakeSQS{err: errors.New("queue unavailable")}
	router := setupAuditRouter(sqsClient, "/api/clients/:clientId", http.MethodDelete, func(c *gin.Context) {
		c.Status(http.StatusNoContent)
	})

	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest(http.MethodDelete, "/api/clients/client-123", nil))

	assert.Equal(t, http.StatusNoContent, response.Code)
	assert.Empty(t, sqsClient.inputs)
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
