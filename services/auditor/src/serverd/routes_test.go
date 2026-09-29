package main

import (
	"auditor/src/internal/controller/corrupted_log"
	"auditor/src/internal/models"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestHealthHandler(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/health", nil)
	recorder := httptest.NewRecorder()

	newHandler(corrupted_log.Controller{}).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected status %d, got %d", http.StatusOK, recorder.Code)
	}
	if got := recorder.Body.String(); got != "{\"status\":\"ok\"}" {
		t.Fatalf("expected health response, got %q", got)
	}
}

type routeService struct {
	deletedID string
}

func (s *routeService) GetCorruptedAuditLog(context.Context) ([]models.CorruptedAuditLog, error) {
	return []models.CorruptedAuditLog{}, nil
}

func (s *routeService) InsertCorruptedAuditLog(context.Context, models.CorruptedAuditLog) error {
	return nil
}

func (s *routeService) DeleteCorruptedAuditLog(_ context.Context, id string) error {
	s.deletedID = id
	return nil
}

func TestCorruptedLogRoutes(t *testing.T) {
	svc := &routeService{}
	handler := newHandler(corrupted_log.New(svc))

	tests := map[string]struct {
		method string
		path   string
		status int
	}{
		"get collection":    {method: http.MethodGet, path: "/api/corrupted-logs", status: http.StatusOK},
		"create collection": {method: http.MethodPost, path: "/api/corrupted-logs", status: http.StatusBadRequest},
		"delete item":       {method: http.MethodDelete, path: "/api/corrupted-logs/123e4567-e89b-12d3-a456-426614174000", status: http.StatusOK},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			request := httptest.NewRequest(test.method, test.path, nil)
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			assert.Equal(t, test.status, response.Code)
		})
	}
	assert.Equal(t, "123e4567-e89b-12d3-a456-426614174000", svc.deletedID)
}
