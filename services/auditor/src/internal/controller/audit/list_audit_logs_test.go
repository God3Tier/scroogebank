package audit

import (
	"context"
	"testing"
	"time"

	auditv1 "auditor/src/gen/audit/v1"
	"auditor/src/internal/models"
	"auditor/src/internal/security"
	auditSvc "auditor/src/internal/service/audit"

	"github.com/stretchr/testify/assert"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestListAuditLogs(t *testing.T) {
	ctx := security.WithCaller(context.Background(), admin)
	at := time.Date(2026, 11, 7, 4, 0, 0, 0, time.UTC)
	svc := auditSvc.NewMockService(t)
	svc.EXPECT().ListAuditLogs(ctx, models.AuditLogFilter{BeforeSequenceNo: 12, Limit: 2}).Return(
		[]models.AuditLog{
			{ID: "id-11", SequenceNo: 11, EventType: "CRUD", Action: "READ", ActorID: "admin-sub",
				SourceService: models.TransactionServiceType, Timestamp: at, Payload: `{"status_code":200}`},
			{ID: "id-10", SequenceNo: 10, EventType: "TRANSACTION_INGESTION", Action: "FILE_PROCESSING_COMPLETED",
				ActorID: "system", SourceService: models.TransactionServiceType, Timestamp: at, Payload: `{}`},
		}, nil).Once()

	actual, err := New(svc).ListAuditLogs(ctx, &auditv1.ListAuditLogsRequest{PageSize: 1, PageToken: "12"})

	assert.NoError(t, err)
	assert.Len(t, actual.Entries, 1)
	assert.Equal(t, "TRANSACTION_SERVICE", actual.Entries[0].SourceService)
	assert.Equal(t, "CRUD", actual.Entries[0].EventType)
	assert.Equal(t, "11", actual.NextPageToken)
}

func TestListAuditLogsRefusesAgents(t *testing.T) {
	ctx := security.WithCaller(context.Background(), agent)
	svc := auditSvc.NewMockService(t)

	actual, err := New(svc).ListAuditLogs(ctx, &auditv1.ListAuditLogsRequest{PageSize: 20})

	assert.Nil(t, actual)
	assert.Equal(t, codes.PermissionDenied, status.Code(err))
}

func TestListAuditLogsRequiresVerifiedCaller(t *testing.T) {
	svc := auditSvc.NewMockService(t)

	actual, err := New(svc).ListAuditLogs(context.Background(), &auditv1.ListAuditLogsRequest{PageSize: 20})

	assert.Nil(t, actual)
	assert.Equal(t, codes.Unauthenticated, status.Code(err))
}
