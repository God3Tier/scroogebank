package audit

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetChainPage(t *testing.T) {
	tests := map[string]struct {
		after   int64
		limit   int
		wantSeq []int64
	}{
		"returns the first page, oldest first":        {after: 0, limit: 2, wantSeq: []int64{1, 2}},
		"returns the entries after a sequence number": {after: 2, limit: 2, wantSeq: []int64{3}},
		"returns nothing past the newest entry":       {after: 3, limit: 2, wantSeq: []int64{}},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, "get_chain.sql")

			actual, err := New(db).GetChainPage(context.Background(), test.after, test.limit)

			require.NoError(t, err)
			gotSeq := make([]int64, 0, len(actual))
			for _, entry := range actual {
				gotSeq = append(gotSeq, entry.SequenceNo)
			}
			assert.Equal(t, test.wantSeq, gotSeq)
		})
	}
}

func TestGetChainPageMapsEveryField(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "get_chain.sql")

	actual, err := New(db).GetChainPage(context.Background(), 2, 1)

	require.NoError(t, err)
	require.Len(t, actual, 1)
	entry := actual[0]
	assert.Equal(t, "00000000-0000-0000-0000-000000000203", entry.ID)
	assert.Equal(t, int64(3), entry.SequenceNo)
	assert.Equal(t, "", entry.EventID)
	assert.Equal(t, "AUTH", entry.EventType)
	assert.Equal(t, "LOGIN_FAILED", entry.Action)
	assert.Equal(t, "AUDIT_SERVICE", string(entry.SourceService))
	assert.Equal(t, "actor-3", entry.ActorID)
	assert.Equal(t, "2026-09-16T05:02:00Z", entry.Timestamp.UTC().Format("2006-01-02T15:04:05Z07:00"))
	assert.Equal(t, `{}`, entry.Payload)
	assert.Equal(t, "chain-hash-2", entry.ParentHash)
	assert.Equal(t, "chain-hash-3", entry.Hash)
}
