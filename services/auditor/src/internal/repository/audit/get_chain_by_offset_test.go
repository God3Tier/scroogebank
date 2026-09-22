package audit

import (
	"context"
	"testing"
	"time"

	"auditor/src/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetChainByOffset(t *testing.T) {
	timestamp1 := time.Date(2026, 9, 16, 5, 0, 0, 0, time.UTC)
	timestamp2 := time.Date(2026, 9, 16, 5, 1, 0, 0, time.UTC)
	timestamp3 := time.Date(2026, 9, 16, 5, 2, 0, 0, time.UTC)

	tests := map[string]struct {
		fixture    string
		startChain int
		offset     int
		want       []models.AuditLog
	}{
		"returns entire chain": {
			fixture:    "get_chain.sql",
			startChain: 1,
			offset:     2,
			want: []models.AuditLog{
				{
					Id:            "00000000-0000-0000-0000-000000000201",
					SequenceNo:    1,
					EventType:     "AUTH",
					Action:        "LOGIN_FAILED",
					SourceService: models.AuditService,
					ActorId:       "actor-1",
					Timestamp:     timestamp1,
					Payload:       "{}",
					ParentHash:    "",
					Hash:          "chain-hash-1",
				},
				{
					Id:            "00000000-0000-0000-0000-000000000202",
					SequenceNo:    2,
					EventType:     "AUTH",
					Action:        "LOGIN_FAILED",
					SourceService: models.AuditService,
					ActorId:       "actor-2",
					Timestamp:     timestamp2,
					Payload:       "{}",
					ParentHash:    "chain-hash-1",
					Hash:          "chain-hash-2",
				},
				{
					Id:            "00000000-0000-0000-0000-000000000203",
					SequenceNo:    3,
					EventType:     "AUTH",
					Action:        "LOGIN_FAILED",
					SourceService: models.AuditService,
					ActorId:       "actor-3",
					Timestamp:     timestamp3,
					Payload:       "{}",
					ParentHash:    "chain-hash-2",
					Hash:          "chain-hash-3",
				},
			},
		},

		"returns chain starting from sequence 2": {
			fixture:    "get_chain.sql",
			startChain: 2,
			offset:     1,
			want: []models.AuditLog{
				{
					Id:            "00000000-0000-0000-0000-000000000202",
					SequenceNo:    2,
					EventType:     "AUTH",
					Action:        "LOGIN_FAILED",
					SourceService: models.AuditService,
					ActorId:       "actor-2",
					Timestamp:     timestamp2,
					Payload:       "{}",
					ParentHash:    "chain-hash-1",
					Hash:          "chain-hash-2",
				},
				{
					Id:            "00000000-0000-0000-0000-000000000203",
					SequenceNo:    3,
					EventType:     "AUTH",
					Action:        "LOGIN_FAILED",
					SourceService: models.AuditService,
					ActorId:       "actor-3",
					Timestamp:     timestamp3,
					Payload:       "{}",
					ParentHash:    "chain-hash-2",
					Hash:          "chain-hash-3",
				},
			},
		},

		"returns a single chain entry": {
			fixture:    "get_chain.sql",
			startChain: 2,
			offset:     0,
			want: []models.AuditLog{
				{
					Id:            "00000000-0000-0000-0000-000000000202",
					SequenceNo:    2,
					EventType:     "AUTH",
					Action:        "LOGIN_FAILED",
					SourceService: models.AuditService,
					ActorId:       "actor-2",
					Timestamp:     timestamp2,
					Payload:       "{}",
					ParentHash:    "chain-hash-1",
					Hash:          "chain-hash-2",
				},
			},
		},

		"returns empty result when start is beyond chain": {
			fixture:    "get_chain.sql",
			startChain: 4,
			offset:     2,
			want:       []models.AuditLog{},
		},

		"returns entries up to end of chain when offset exceeds chain": {
			fixture:    "get_chain.sql",
			startChain: 2,
			offset:     10,
			want: []models.AuditLog{
				{
					Id:            "00000000-0000-0000-0000-000000000202",
					SequenceNo:    2,
					EventType:     "AUTH",
					Action:        "LOGIN_FAILED",
					SourceService: models.AuditService,
					ActorId:       "actor-2",
					Timestamp:     timestamp2,
					Payload:       "{}",
					ParentHash:    "chain-hash-1",
					Hash:          "chain-hash-2",
				},
				{
					Id:            "00000000-0000-0000-0000-000000000203",
					SequenceNo:    3,
					EventType:     "AUTH",
					Action:        "LOGIN_FAILED",
					SourceService: models.AuditService,
					ActorId:       "actor-3",
					Timestamp:     timestamp3,
					Payload:       "{}",
					ParentHash:    "chain-hash-2",
					Hash:          "chain-hash-3",
				},
			},
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)

			actual, err := New(db).GetChainByOffset(
				context.Background(),
				test.startChain,
				test.offset,
			)

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
