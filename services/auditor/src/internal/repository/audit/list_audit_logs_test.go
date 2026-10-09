package audit

import (
	"context"
	"testing"
	"time"

	"auditor/src/internal/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestListAuditLogs(t *testing.T) {
	tests := map[string]struct {
		filter  models.AuditLogFilter
		wantSeq []int64
	}{
		"returns everything newest first": {
			filter:  models.AuditLogFilter{Limit: 10},
			wantSeq: []int64{4, 3, 2, 1},
		},
		"filters by actor": {
			filter:  models.AuditLogFilter{ActorId: "agent-a", Limit: 10},
			wantSeq: []int64{4, 3, 1},
		},
		"filters by time range with inclusive from and exclusive to": {
			filter: models.AuditLogFilter{
				From:  time.Date(2026, 9, 11, 4, 0, 0, 0, time.UTC),
				To:    time.Date(2026, 9, 20, 4, 0, 0, 0, time.UTC),
				Limit: 10,
			},
			wantSeq: []int64{3, 2},
		},
		"pages by sequence number": {
			filter:  models.AuditLogFilter{BeforeSequenceNo: 3, Limit: 1},
			wantSeq: []int64{2},
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, "list_audit_logs.sql")

			actual, err := New(db).ListAuditLogs(context.Background(), test.filter)

			require.NoError(t, err)
			gotSeq := make([]int64, 0, len(actual))
			for _, entry := range actual {
				gotSeq = append(gotSeq, entry.SequenceNo)
			}
			assert.Equal(t, test.wantSeq, gotSeq)
		})
	}
}
