package audit

import (
	"context"
	"testing"
	"time"

	"auditor/src/internal/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetAuditByService(t *testing.T) {
	timestamp := time.Date(2026, 9, 16, 4, 0, 0, 0, time.UTC)
	tests := map[string]struct {
		fixture string
		service models.ServiceType
		want    []models.AuditLog
	}{
		"returns mapped CRM logs": {
			fixture: "get_audit_by_service.sql",
			service: models.CRMServiceType,
			want: []models.AuditLog{{
				Id: "00000000-0000-0000-0000-000000000101", SequenceNo: 1,
				EventType: "CRUD", Action: "CREATE", SourceService: models.CRMServiceType,
				ActorId: "actor-crm", Timestamp: timestamp, Payload: `{"lead": "lead-101"}`,
				ParentHash: "", Hash: "crm-hash-1",
			}},
		},
		"returns no logs for a service with no matching rows": {
			fixture: "get_audit_by_service_empty.sql",
			service: models.CRMServiceType,
			want:    []models.AuditLog{},
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)

			actual, err := New(db).GetAuditByService(context.Background(), test.service)

			require.NoError(t, err)
			require.Len(t, actual, len(test.want))
			for index, expected := range test.want {
				assert.Equal(t, expected.Id, actual[index].Id)
				assert.Equal(t, expected.SequenceNo, actual[index].SequenceNo)
				assert.Equal(t, expected.EventType, actual[index].EventType)
				assert.Equal(t, expected.Action, actual[index].Action)
				assert.Equal(t, expected.SourceService, actual[index].SourceService)
				assert.Equal(t, expected.ActorId, actual[index].ActorId)
				assert.True(t, expected.Timestamp.Equal(actual[index].Timestamp))
				assert.Equal(t, expected.Payload, actual[index].Payload)
				assert.Equal(t, expected.ParentHash, actual[index].ParentHash)
				assert.Equal(t, expected.Hash, actual[index].Hash)
			}
		})
	}
}
