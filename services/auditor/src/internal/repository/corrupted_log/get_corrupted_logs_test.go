package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetCorruptedLogs(t *testing.T) {
	tests := map[string]struct {
		fixture string
		want    []models.CorruptedAuditLog
	}{
		"returns valid records and skips malformed payloads": {
			fixture: "get_corrupted_logs.sql",
			want: []models.CorruptedAuditLog{{
				ID: "00000000-0000-0000-0000-000000000102",
				Payload: models.AuditLog{
					Id:         "audit-102",
					SequenceNo: 102,
					EventType:  "AUTH",
					Action:     "LOGIN_FAILED",
				},
				DateOfDetect: time.Date(2026, 9, 29, 10, 0, 0, 0, time.UTC),
			}, {
				ID:         "00000000-0000-0000-0000-000000000106",
				AuditLogID: "00000000-0000-0000-0000-000000000206",
				Reason:     models.HashMismatch,
				Payload: models.AuditLog{
					Id:         "00000000-0000-0000-0000-000000000206",
					SequenceNo: 206,
					EventType:  "CRUD",
					Action:     "UPDATE",
				},
				DateOfDetect: time.Date(2026, 9, 29, 10, 2, 0, 0, time.UTC),
			}},
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)

			actual, err := (impl{db: db}).GetCorruptedLogs(context.Background())

			require.NoError(t, err)
			require.Len(t, actual, len(test.want))
			for index, expected := range test.want {
				assert.Equal(t, expected.ID, actual[index].ID)
				assert.Equal(t, expected.AuditLogID, actual[index].AuditLogID)
				assert.Equal(t, expected.Reason, actual[index].Reason)
				assert.Equal(t, expected.Payload, actual[index].Payload)
				assert.True(t, expected.DateOfDetect.Equal(actual[index].DateOfDetect))
			}
		})
	}
}
