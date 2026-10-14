package audit

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	auditv1 "auditor/src/gen/audit/v1"
	"auditor/src/internal/models"
	"auditor/src/internal/security"
	auditSvc "auditor/src/internal/service/audit"

	"github.com/stretchr/testify/assert"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
)

var (
	admin = security.Principal{Subject: "admin-sub", Role: "ADMIN"}
	agent = security.Principal{Subject: "agent-sub-1", Role: "AGENT"}
)

func TestListActivities(t *testing.T) {
	from := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)
	to := time.Date(2026, 9, 30, 0, 0, 0, 0, time.UTC)
	at := time.Date(2026, 9, 16, 4, 0, 0, 0, time.UTC)

	entry := func(seq int64) models.AuditLog {
		return models.AuditLog{
			ID:            fmt.Sprintf("id-%d", seq),
			SequenceNo:    seq,
			EventType:     "CRUD",
			Action:        "UPDATE",
			ActorID:       "agent-sub-1",
			SourceService: models.CRMServiceType,
			Timestamp:     at,
			Payload:       `{"client_id":"c-1"}`,
		}
	}
	activity := func(seq int64) *auditv1.Activity {
		return &auditv1.Activity{
			Id:            fmt.Sprintf("id-%d", seq),
			SourceService: "CRM",
			Action:        "UPDATE",
			ActorId:       "agent-sub-1",
			Timestamp:     timestamppb.New(at),
			Payload:       `{"client_id":"c-1"}`,
		}
	}

	tests := map[string]struct {
		caller       *security.Principal // nil means an admin
		noCaller     bool
		request      *auditv1.ListActivitiesRequest
		wantFilter   *models.ActivityFilter
		serviceLogs  []models.AuditLog
		serviceError error
		wantCode     codes.Code
		wantResponse *auditv1.ListActivitiesResponse
	}{
		"rejects zero page size": {
			request:  &auditv1.ListActivitiesRequest{},
			wantCode: codes.InvalidArgument,
		},
		"rejects oversized page": {
			request:  &auditv1.ListActivitiesRequest{PageSize: 101},
			wantCode: codes.InvalidArgument,
		},
		"rejects a page token that is not one": {
			request:  &auditv1.ListActivitiesRequest{PageSize: 10, PageToken: "not-a-token"},
			wantCode: codes.InvalidArgument,
		},
		"rejects from after to": {
			request: &auditv1.ListActivitiesRequest{
				PageSize: 10, From: timestamppb.New(to), To: timestamppb.New(from),
			},
			wantCode: codes.InvalidArgument,
		},
		"passes filters and returns the last page": {
			request: &auditv1.ListActivitiesRequest{
				PageSize: 2, ActorId: "agent-sub-1",
				From: timestamppb.New(from), To: timestamppb.New(to), PageToken: "9",
			},
			wantFilter: &models.ActivityFilter{
				ActorID: "agent-sub-1", From: from, To: to, BeforeSequenceNo: 9, Limit: 3,
			},
			serviceLogs: []models.AuditLog{entry(8), entry(7)},
			wantResponse: &auditv1.ListActivitiesResponse{
				Activities: []*auditv1.Activity{activity(8), activity(7)},
			},
		},
		"trims the extra row and returns a page token": {
			request:     &auditv1.ListActivitiesRequest{PageSize: 2},
			wantFilter:  &models.ActivityFilter{Limit: 3},
			serviceLogs: []models.AuditLog{entry(5), entry(4), entry(3)},
			wantResponse: &auditv1.ListActivitiesResponse{
				Activities:    []*auditv1.Activity{activity(5), activity(4)},
				NextPageToken: "4",
			},
		},
		"maps a service error to internal": {
			request:      &auditv1.ListActivitiesRequest{PageSize: 2},
			wantFilter:   &models.ActivityFilter{Limit: 3},
			serviceError: errors.New("database unavailable"),
			wantCode:     codes.Internal,
		},
		"limits an agent to their own entries": {
			caller:      &agent,
			request:     &auditv1.ListActivitiesRequest{PageSize: 2},
			wantFilter:  &models.ActivityFilter{ActorID: "agent-sub-1", Limit: 3},
			serviceLogs: []models.AuditLog{entry(5)},
			wantResponse: &auditv1.ListActivitiesResponse{
				Activities: []*auditv1.Activity{activity(5)},
			},
		},
		"lets an agent name themselves": {
			caller:       &agent,
			request:      &auditv1.ListActivitiesRequest{PageSize: 2, ActorId: "agent-sub-1"},
			wantFilter:   &models.ActivityFilter{ActorID: "agent-sub-1", Limit: 3},
			wantResponse: &auditv1.ListActivitiesResponse{Activities: []*auditv1.Activity{}},
		},
		"refuses an agent asking for another agent": {
			caller:   &agent,
			request:  &auditv1.ListActivitiesRequest{PageSize: 2, ActorId: "agent-sub-2"},
			wantCode: codes.PermissionDenied,
		},
		"refuses a call with no verified caller": {
			noCaller: true,
			request:  &auditv1.ListActivitiesRequest{PageSize: 2},
			wantCode: codes.Unauthenticated,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			ctx := context.Background()
			if !test.noCaller {
				caller := admin
				if test.caller != nil {
					caller = *test.caller
				}
				ctx = security.WithCaller(ctx, caller)
			}
			svc := auditSvc.NewMockService(t)
			if test.wantFilter != nil {
				svc.EXPECT().
					ListActivities(ctx, *test.wantFilter).
					Return(test.serviceLogs, test.serviceError).
					Once()
			}

			actual, err := New(svc).ListActivities(ctx, test.request)

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
