package controller

import (
	"auditor/src/gen"
	"auditor/src/internal/models"
	"auditor/src/internal/service"
	"context"
	"database/sql"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
)

func TestGetAuditLog(t *testing.T) {
	ctx := context.Background()
	timestamp := time.Date(2026, 9, 16, 4, 0, 0, 0, time.UTC)
	databaseErr := errors.New("database unavailable")

	logs := []service.RetrieveAuditLogResponse{
		{
			EventType: "CRUD",
			Action:    "CREATE_LEAD",
			Timestamp: timestamp,
			Payload:   `{"lead":"1"}`,
		},
		{
			EventType: "CRUD",
			Action:    "UPDATE_LEAD",
			Timestamp: timestamp.Add(time.Minute),
			Payload:   `{"lead":"1"}`,
		},
	}

	tests := map[string]struct {
		request      *gen.GetAuditLogRequest
		serviceLogs  []service.RetrieveAuditLogResponse
		serviceError error
		wantCode     codes.Code
		wantResponse *gen.GetAuditLogResponse
		callService  bool
	}{
		"rejects empty service": {
			request:  &gen.GetAuditLogRequest{},
			wantCode: codes.InvalidArgument,
		},

		"rejects unknown service": {
			request:  &gen.GetAuditLogRequest{Service: "UNKNOWN_SERVICE"},
			wantCode: codes.InvalidArgument,
		},

		"returns mapped audit logs": {
			request: &gen.GetAuditLogRequest{
				Service: "CRM",
			},
			serviceLogs: logs,
			wantResponse: &gen.GetAuditLogResponse{
				Logs: []*gen.AuditLog{
					{
						EventType: "CRUD",
						Action:    "CREATE_LEAD",
						Timestamp: timestampProto(timestamp),
						Payload:   `{"lead":"1"}`,
					},
					{
						EventType: "CRUD",
						Action:    "UPDATE_LEAD",
						Timestamp: timestampProto(timestamp.Add(time.Minute)),
						Payload:   `{"lead":"1"}`,
					},
				},
			},
			callService: true,
		},

		"returns empty logs": {
			request:      &gen.GetAuditLogRequest{Service: "CRM"},
			serviceLogs:  []service.RetrieveAuditLogResponse{},
			wantResponse: &gen.GetAuditLogResponse{Logs: []*gen.AuditLog{}},
			callService:  true,
		},

		"maps no rows to not found": {
			request:      &gen.GetAuditLogRequest{Service: "CRM"},
			serviceError: sql.ErrNoRows,
			wantCode:     codes.NotFound,
			callService:  true,
		},

		"maps service error to internal": {
			request:      &gen.GetAuditLogRequest{Service: "CRM"},
			serviceError: databaseErr,
			wantCode:     codes.Internal,
			callService:  true,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := service.NewMockAudit(t)

			if test.callService {
				svc.EXPECT().
					RetrieveAuditLog(
						ctx,
						models.ServiceType(test.request.Service),
					).
					Return(test.serviceLogs, test.serviceError).
					Once()
			}

			controller := New(svc)

			actual, err := controller.GetAuditLog(ctx, test.request)

			if test.wantCode != codes.OK {
				assert.Nil(t, actual)
				assert.Equal(t, test.wantCode, status.Code(err))
				return
			}

			assert.NoError(t, err)
			assert.Equal(t, test.wantResponse, actual)
		})
	}
}

func timestampProto(timestamp time.Time) *timestamppb.Timestamp {
	return timestamppb.New(timestamp)
}
