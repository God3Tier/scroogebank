package service

import (
	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestRetrieveAuditLog(t *testing.T) {
	ctx := context.Background()
	timestamp := time.Date(2026, 9, 15, 4, 0, 0, 0, time.UTC)
	databaseErr := errors.New("database unavailable")
	rawLogs := []models.AuditLog{
		{
			EventType: "CRUD", Action: "CREATE_LEAD", SourceService: models.CRMServiceType,
			ActorId: "actor-1", Timestamp: timestamp, Payload: `{"lead":"1"}`,
		},
		{
			EventType: "CRUD", Action: "UPDATE_LEAD", SourceService: models.CRMServiceType,
			ActorId: "actor-2", Timestamp: timestamp.Add(time.Minute), Payload: `{"lead":"1"}`,
		},
	}
	wantLogs := []RetrieveAuditLogResponse{
		{EventType: "CRUD", Action: "CREATE_LEAD", Timestamp: timestamp, Payload: `{"lead":"1"}`},
		{EventType: "CRUD", Action: "UPDATE_LEAD", Timestamp: timestamp.Add(time.Minute), Payload: `{"lead":"1"}`},
	}

	tests := map[string]struct {
		service  models.ServiceType
		repoLogs []models.AuditLog
		repoErr  error
		want     []RetrieveAuditLogResponse
		wantErr  error
		callRepo bool
	}{
		"returns logs for user management service": {
			service: models.UserManagementServiceType, want: []RetrieveAuditLogResponse{}, callRepo: true,
		},
		"returns logs for CRM service": {
			service: models.CRMServiceType, repoLogs: rawLogs, want: wantLogs, callRepo: true,
		},
		"returns logs for transaction service": {
			service: models.TransactionServiceType, want: []RetrieveAuditLogResponse{}, callRepo: true,
		},
		"returns logs for audit service": {
			service: models.AuditService, want: []RetrieveAuditLogResponse{}, callRepo: true,
		},
		"returns empty response when repository returns no logs": {
			service: models.CRMServiceType, repoLogs: []models.AuditLog{}, want: []RetrieveAuditLogResponse{}, callRepo: true,
		},
		"returns repository error": {
			service: models.CRMServiceType, repoErr: databaseErr, wantErr: databaseErr, callRepo: true,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := audit.NewMockRepository(t)

			t.Cleanup(func() { repo.AssertExpectations(t) })
			if test.callRepo {
				repo.On("GetAuditByService", ctx, models.ServiceType(test.service)).Return(test.repoLogs, test.repoErr).Once()
			}

			actual, err := New(repo).RetrieveAuditLog(ctx, test.service)

			assert.ErrorIs(t, err, test.wantErr)
			if test.wantErr == nil {
				assert.Equal(t, test.want, actual)
			} else {
				assert.Nil(t, actual)
			}
		})
	}
}

func TestMapAuditLog(t *testing.T) {
	timestamp := time.Date(2026, 9, 15, 4, 0, 0, 0, time.UTC)

	actual := mapAuditLog(models.AuditLog{
		EventType: "SECURITY", Action: "LOGIN_FAILED", Timestamp: timestamp, Payload: `{"ip":"127.0.0.1"}`,
	})

	assert.Equal(t, RetrieveAuditLogResponse{
		EventType: "SECURITY", Action: "LOGIN_FAILED", Timestamp: timestamp, Payload: `{"ip":"127.0.0.1"}`,
	}, actual)
}
