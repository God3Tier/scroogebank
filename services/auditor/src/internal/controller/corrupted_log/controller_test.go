package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeService struct {
	logs         []models.CorruptedAuditLog
	getErr       error
	insertErr    error
	deleteErr    error
	inserted     models.CorruptedAuditLog
	deletedID    string
	getCalled    bool
	insertCalled bool
	deleteCalled bool
}

func (f *fakeService) GetCorruptedAuditLog(context.Context) ([]models.CorruptedAuditLog, error) {
	f.getCalled = true
	return f.logs, f.getErr
}

func (f *fakeService) InsertCorruptedAuditLog(_ context.Context, raw models.CorruptedAuditLog) error {
	f.insertCalled = true
	f.inserted = raw
	return f.insertErr
}

func (f *fakeService) DeleteCorruptedAuditLog(_ context.Context, id string) error {
	f.deleteCalled = true
	f.deletedID = id
	return f.deleteErr
}

func TestGetCorruptedAuditLog(t *testing.T) {
	tests := map[string]struct {
		logs       []models.CorruptedAuditLog
		serviceErr error
		wantStatus int
		wantBody   string
	}{
		"returns corrupted logs": {
			logs:       []models.CorruptedAuditLog{{ID: "log-1"}},
			wantStatus: http.StatusOK,
			wantBody:   `"id":"log-1"`,
		},
		"returns an empty list": {
			logs:       []models.CorruptedAuditLog{},
			wantStatus: http.StatusOK,
			wantBody:   `[]`,
		},
		"maps service failure": {
			serviceErr: errors.New("database unavailable"),
			wantStatus: http.StatusInternalServerError,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := &fakeService{logs: test.logs, getErr: test.serviceErr}
			request := httptest.NewRequest(http.MethodGet, "/api/corrupted-logs", nil)
			response := httptest.NewRecorder()

			New(svc).GetCorruptedAuditLog(response, request)

			assert.Equal(t, test.wantStatus, response.Code)
			assert.True(t, svc.getCalled)
			if test.wantBody != "" {
				assert.Contains(t, response.Body.String(), test.wantBody)
			}
		})
	}
}

func TestInsertCorruptedAuditLog(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	tests := map[string]struct {
		body       string
		serviceErr error
		wantStatus int
		callSvc    bool
	}{
		"creates a corrupted log": {
			body:       `{"id":"` + id + `","payload":{"Action":"DELETE"}}`,
			wantStatus: http.StatusCreated,
			callSvc:    true,
		},
		"rejects invalid JSON": {
			body:       `{`,
			wantStatus: http.StatusBadRequest,
		},
		"rejects invalid UUID": {
			body:       `{"id":"bad-id","payload":{}}`,
			wantStatus: http.StatusBadRequest,
		},
		"maps service failure": {
			body:       `{"id":"` + id + `","payload":{}}`,
			serviceErr: errors.New("database unavailable"),
			wantStatus: http.StatusInternalServerError,
			callSvc:    true,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := &fakeService{insertErr: test.serviceErr}
			request := httptest.NewRequest(http.MethodPost, "/api/corrupted-logs", strings.NewReader(test.body))
			response := httptest.NewRecorder()

			New(svc).InsertCorruptedAuditLog(response, request)

			assert.Equal(t, test.wantStatus, response.Code)
			assert.Equal(t, test.callSvc, svc.insertCalled)
			if test.callSvc && test.serviceErr == nil {
				assert.Equal(t, id, svc.inserted.ID)
				assert.Equal(t, "DELETE", svc.inserted.Payload.Action)
			}
		})
	}
}

func TestDeleteCorruptedAuditLog(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	tests := map[string]struct {
		url        string
		serviceErr error
		wantStatus int
		callSvc    bool
	}{
		"deletes a corrupted log": {
			url:        "/api/corrupted-logs?id=" + id,
			wantStatus: http.StatusOK,
			callSvc:    true,
		},
		"requires an id": {
			url:        "/api/corrupted-logs",
			wantStatus: http.StatusBadRequest,
		},
		"rejects invalid UUID": {
			url:        "/api/corrupted-logs?id=bad-id",
			wantStatus: http.StatusBadRequest,
		},
		"maps missing log": {
			url:        "/api/corrupted-logs?id=" + id,
			serviceErr: sql.ErrNoRows,
			wantStatus: http.StatusNotFound,
			callSvc:    true,
		},
		"maps service failure": {
			url:        "/api/corrupted-logs?id=" + id,
			serviceErr: errors.New("database unavailable"),
			wantStatus: http.StatusInternalServerError,
			callSvc:    true,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := &fakeService{deleteErr: test.serviceErr}
			request := httptest.NewRequest(http.MethodDelete, test.url, nil)
			response := httptest.NewRecorder()

			New(svc).DeleteCorruptedAuditLog(response, request)

			assert.Equal(t, test.wantStatus, response.Code)
			assert.Equal(t, test.callSvc, svc.deleteCalled)
			if test.callSvc {
				assert.Equal(t, id, svc.deletedID)
			}
		})
	}
}

func TestCorruptedAuditLogResponseIsJSON(t *testing.T) {
	svc := &fakeService{logs: []models.CorruptedAuditLog{{ID: "log-2"}}}
	request := httptest.NewRequest(http.MethodGet, "/api/corrupted-logs", nil)
	response := httptest.NewRecorder()

	New(svc).GetCorruptedAuditLog(response, request)

	var logs []models.CorruptedAuditLog
	require.NoError(t, json.Unmarshal(response.Body.Bytes(), &logs))
	require.Len(t, logs, 1)
	assert.Equal(t, "log-2", logs[0].ID)
	assert.Equal(t, "application/json", response.Header().Get("Content-Type"))
}
