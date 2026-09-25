package controller

import (
	"auditor/src/gen"
	"auditor/src/internal/models"
	"auditor/src/internal/service"
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
)

func TestListAuditLogs(t *testing.T) {
	ctx := context.Background()
	from := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)
	to := time.Date(2026, 9, 30, 0, 0, 0, 0, time.UTC)
	at := time.Date(2026, 9, 16, 4, 0, 0, 0, time.UTC)

	entry := func(seq int64) models.AuditLog {
		return models.AuditLog{
			Id:            fmt.Sprintf("id-%d", seq),
			SequenceNo:    seq,
			EventType:     "CRUD",
			Action:        "UPDATE_CLIENT",
			ActorId:       "agent-1",
			SourceService: models.CRMServiceType,
			Timestamp:     at,
			Payload:       `{"client_id":"c-1"}`,
		}
	}
	protoEntry := func(seq int64) *gen.AuditLogEntry {
		return &gen.AuditLogEntry{
			Id:            fmt.Sprintf("id-%d", seq),
			SequenceNo:    seq,
			EventType:     "CRUD",
			Action:        "UPDATE_CLIENT",
			SourceService: "CRM",
			ActorId:       "agent-1",
			Timestamp:     timestamppb.New(at),
			Payload:       `{"client_id":"c-1"}`,
		}
	}

	tests := map[string]struct {
		request      *gen.ListAuditLogsRequest
		wantFilter   *models.AuditLogFilter
		serviceLogs  []models.AuditLog
		serviceError error
		wantCode     codes.Code
		wantResponse *gen.ListAuditLogsResponse
	}{
		"rejects zero page size": {
			request:  &gen.ListAuditLogsRequest{},
			wantCode: codes.InvalidArgument,
		},

		"rejects oversized page": {
			request:  &gen.ListAuditLogsRequest{PageSize: 101},
			wantCode: codes.InvalidArgument,
		},

		"rejects unknown service": {
			request:  &gen.ListAuditLogsRequest{PageSize: 10, SourceService: "UNKNOWN"},
			wantCode: codes.InvalidArgument,
		},

		"rejects from after to": {
			request: &gen.ListAuditLogsRequest{
				PageSize: 10, From: timestamppb.New(to), To: timestamppb.New(from),
			},
			wantCode: codes.InvalidArgument,
		},

		"passes filters and returns last page": {
			request: &gen.ListAuditLogsRequest{
				PageSize: 2, SourceService: "CRM", ActorId: "agent-1",
				From: timestamppb.New(from), To: timestamppb.New(to), BeforeSequenceNo: 9,
			},
			wantFilter: &models.AuditLogFilter{
				SourceService: models.CRMServiceType, ActorId: "agent-1",
				From: from, To: to, BeforeSequenceNo: 9, Limit: 3,
			},
			serviceLogs: []models.AuditLog{entry(8), entry(7)},
			wantResponse: &gen.ListAuditLogsResponse{
				Entries: []*gen.AuditLogEntry{protoEntry(8), protoEntry(7)},
			},
		},

		"trims extra row and returns cursor": {
			request:     &gen.ListAuditLogsRequest{PageSize: 2},
			wantFilter:  &models.AuditLogFilter{Limit: 3},
			serviceLogs: []models.AuditLog{entry(5), entry(4), entry(3)},
			wantResponse: &gen.ListAuditLogsResponse{
				Entries:              []*gen.AuditLogEntry{protoEntry(5), protoEntry(4)},
				NextBeforeSequenceNo: 4,
			},
		},

		"maps service error to internal": {
			request:      &gen.ListAuditLogsRequest{PageSize: 2},
			wantFilter:   &models.AuditLogFilter{Limit: 3},
			serviceError: errors.New("database unavailable"),
			wantCode:     codes.Internal,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := service.NewMockAudit(t)

			if test.wantFilter != nil {
				svc.EXPECT().
					ListAuditLogs(ctx, *test.wantFilter).
					Return(test.serviceLogs, test.serviceError).
					Once()
			}

			actual, err := New(svc).ListAuditLogs(ctx, test.request)

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
