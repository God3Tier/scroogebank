package main

import (
	"auditor/src/internal/controller/corrupted_log"
	"auditor/src/internal/models"
	"auditor/src/internal/security"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
)

// adminVerifier accepts one admin token.
type adminVerifier struct{}

func (adminVerifier) Verify(token string) (security.Principal, error) {
	if token != "admin-token" {
		return security.Principal{}, errors.New("invalid token")
	}
	return security.Principal{Subject: "admin-sub", Role: "ADMIN"}, nil
}

func TestHealthHandler(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/health", nil)
	recorder := httptest.NewRecorder()

	// No token: /health is public.
	newHandler(corrupted_log.Controller{}, adminVerifier{}).ServeHTTP(recorder, request)

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
	handler := newHandler(corrupted_log.New(svc), adminVerifier{})

	tests := map[string]struct {
		method string
		path   string
		token  string
		status int
	}{
		"get collection":         {method: http.MethodGet, path: "/api/corrupted-logs", token: "admin-token", status: http.StatusOK},
		"create collection":      {method: http.MethodPost, path: "/api/corrupted-logs", token: "admin-token", status: http.StatusBadRequest},
		"delete item":            {method: http.MethodDelete, path: "/api/corrupted-logs/123e4567-e89b-12d3-a456-426614174000", token: "admin-token", status: http.StatusOK},
		"refuses no token":       {method: http.MethodGet, path: "/api/corrupted-logs", status: http.StatusUnauthorized},
		"refuses an other token": {method: http.MethodDelete, path: "/api/corrupted-logs/123e4567-e89b-12d3-a456-426614174001", token: "forged", status: http.StatusUnauthorized},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			request := httptest.NewRequest(test.method, test.path, nil)
			if test.token != "" {
				request.Header.Set("Authorization", "Bearer "+test.token)
			}
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			assert.Equal(t, test.status, response.Code)
		})
	}
	assert.Equal(t, "123e4567-e89b-12d3-a456-426614174000", svc.deletedID)
}
