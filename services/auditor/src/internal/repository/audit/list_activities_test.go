package audit

import (
	"context"
	"testing"
	"time"

	"auditor/src/internal/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestListActivities(t *testing.T) {
	tests := map[string]struct {
		filter  models.ActivityFilter
		wantSeq []int64
	}{
		"returns only changes, newest first": {
			// Leaves out the reads, the sign-out, the failed delete, the
			// dashboard view, the ingestion event (5 to 10), the sign-in (12)
			// and the reminder email (15).
			filter:  models.ActivityFilter{Limit: 20},
			wantSeq: []int64{14, 13, 11, 4, 3, 2, 1},
		},
		"shows an agent's calendar change but not the dispatcher's reminder": {
			filter:  models.ActivityFilter{From: time.Date(2026, 9, 24, 0, 0, 0, 0, time.UTC), Limit: 20},
			wantSeq: []int64{14},
		},
		"shows a user's own password reset but not their sign-in": {
			filter:  models.ActivityFilter{ActorID: "agent-c", Limit: 20},
			wantSeq: []int64{13},
		},
		"filters by actor": {
			filter:  models.ActivityFilter{ActorID: "agent-a", Limit: 20},
			wantSeq: []int64{4, 1},
		},
		"filters by client in the audited payload": {
			filter:  models.ActivityFilter{ClientID: "c-1", Limit: 20},
			wantSeq: []int64{4, 1},
		},
		"combines client and agent filters": {
			filter:  models.ActivityFilter{ActorID: "agent-b", ClientID: "c-2", Limit: 20},
			wantSeq: []int64{11, 2},
		},
		"filters by time range with inclusive from and exclusive to": {
			filter: models.ActivityFilter{
				From:  time.Date(2026, 9, 11, 4, 0, 0, 0, time.UTC),
				To:    time.Date(2026, 9, 20, 4, 0, 0, 0, time.UTC),
				Limit: 20,
			},
			wantSeq: []int64{3, 2},
		},
		"pages by sequence number past entries it leaves out": {
			filter:  models.ActivityFilter{BeforeSequenceNo: 11, Limit: 2},
			wantSeq: []int64{4, 3},
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, "list_activities.sql")

			actual, err := New(db).ListActivities(context.Background(), test.filter)

			require.NoError(t, err)
			gotSeq := make([]int64, 0, len(actual))
			for _, entry := range actual {
				gotSeq = append(gotSeq, entry.SequenceNo)
			}
			assert.Equal(t, test.wantSeq, gotSeq)
		})
	}
}

func TestListAuditLogs(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "list_activities.sql")

	actual, err := New(db).ListAuditLogs(context.Background(), models.AuditLogFilter{
		BeforeSequenceNo: 13,
		Limit:            4,
	})

	require.NoError(t, err)
	assert.Equal(t, []int64{12, 11, 10, 9}, []int64{
		actual[0].SequenceNo, actual[1].SequenceNo, actual[2].SequenceNo, actual[3].SequenceNo,
	})
	assert.Equal(t, models.TransactionServiceType, actual[2].SourceService)
	assert.Equal(t, "TRANSACTION_INGESTION", actual[2].EventType)
}
