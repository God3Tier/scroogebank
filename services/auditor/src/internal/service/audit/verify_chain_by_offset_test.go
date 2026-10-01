package audit

import (
	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"
	"auditor/src/internal/service"
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestVerifyChainByOffset(t *testing.T) {
	ctx := context.Background()
	timestamp := time.Date(2026, 9, 15, 5, 0, 0, 0, time.UTC)
	databaseErr := errors.New("database unavailable")

	first := models.AuditLog{
		Id:            "id-1",
		SequenceNo:    1,
		EventType:     "AUTH",
		Action:        "LOGIN_FAILED",
		SourceService: models.AuditService,
		ActorId:       "actor-1",
		Timestamp:     timestamp,
		Payload:       `{}`,
	}
	first.Hash = hashAuditLog(first)

	second := models.AuditLog{
		Id:            "id-2",
		SequenceNo:    2,
		EventType:     "AUTH",
		Action:        "LOGIN_FAILED",
		SourceService: models.AuditService,
		ActorId:       "actor-2",
		Timestamp:     timestamp,
		Payload:       `{}`,
		ParentHash:    first.Hash,
	}
	second.Hash = hashAuditLog(second)

	third := models.AuditLog{
		Id:            "id-3",
		SequenceNo:    3,
		EventType:     "AUTH",
		Action:        "LOGIN_FAILED",
		SourceService: models.AuditService,
		ActorId:       "actor-3",
		Timestamp:     timestamp,
		Payload:       `{}`,
		ParentHash:    second.Hash,
	}
	third.Hash = hashAuditLog(third)

	fourth := models.AuditLog{
		Id:            "id-4",
		SequenceNo:    4,
		EventType:     "AUTH",
		Action:        "LOGIN_FAILED",
		SourceService: models.AuditService,
		ActorId:       "actor-4",
		Timestamp:     timestamp,
		Payload:       `{}`,
		ParentHash:    third.Hash,
	}
	fourth.Hash = hashAuditLog(fourth)

	fifth := models.AuditLog{
		Id:            "id-5",
		SequenceNo:    5,
		EventType:     "AUTH",
		Action:        "LOGIN_FAILED",
		SourceService: models.AuditService,
		ActorId:       "actor-5",
		Timestamp:     timestamp,
		Payload:       `{}`,
		ParentHash:    fourth.Hash,
	}
	fifth.Hash = hashAuditLog(fifth)

	tests := map[string]struct {
		beginChain int
		offset     int
		chain      []models.AuditLog
		repoErr    error
		want       map[string]error
		wantErr    error
	}{
		"returns no mismatch for empty chain": {
			beginChain: 1,
			offset:     2,
			chain:      []models.AuditLog{},
			want:       map[string]error{},
		},

		"returns no mismatch for one log": {
			beginChain: 1,
			offset:     0,
			chain:      []models.AuditLog{first},
			want:       map[string]error{},
		},

		"returns no mismatch for valid chain": {
			beginChain: 1,
			offset:     4,
			chain: []models.AuditLog{
				first,
				second,
				third,
				fourth,
				fifth,
			},
			want: map[string]error{},
		},

		"returns no mismatch for valid partial chain": {
			beginChain: 2,
			offset:     2,
			chain: []models.AuditLog{
				second,
				third,
				fourth,
			},
			want: map[string]error{},
		},

		"returns one mismatch": {
			beginChain: 1,
			offset:     2,
			chain: []models.AuditLog{
				first,
				{
					Id:         "id-2",
					SequenceNo: 2,
					ParentHash: first.Hash,
					Hash:       second.Hash,
				},
				third,
			},
			want: map[string]error{
				second.Hash: service.InvalidChainError("Sequence 1 and 2 out of sync\n"),
			},
		},

		"returns multiple consecutive mismatches": {
			beginChain: 1,
			offset:     3,
			chain: []models.AuditLog{
				first,
				{
					Id:         "id-2",
					SequenceNo: 2,
					ParentHash: first.Hash,
					Hash:       second.Hash,
				},
				{
					Id:         "id-3",
					SequenceNo: 3,
					ParentHash: second.Hash,
					Hash:       third.Hash,
				},
				fourth,
			},
			want: map[string]error{
				second.Hash: service.InvalidChainError("Sequence 1 and 2 out of sync\n"),
				third.Hash:  service.InvalidChainError("Sequence 2 and 3 out of sync\n"),
			},
		},

		"returns multiple non-consecutive mismatches": {
			beginChain: 1,
			offset:     4,
			chain: []models.AuditLog{
				first,
				{
					Id:         "id-2",
					SequenceNo: 2,
					ParentHash: first.Hash,
					Hash:       second.Hash,
				},
				third,
				{
					Id:         "id-4",
					SequenceNo: 4,
					ParentHash: third.Hash,
					Hash:       fourth.Hash,
				},
				fifth,
			},
			want: map[string]error{
				second.Hash: service.InvalidChainError("Sequence 1 and 2 out of sync\n"),
				fourth.Hash: service.InvalidChainError("Sequence 3 and 4 out of sync\n"),
			},
		},

		"returns all mismatches": {
			beginChain: 1,
			offset:     3,
			chain: []models.AuditLog{
				{
					Id:         "id-1",
					SequenceNo: 1,
					Hash:       first.Hash,
				},
				{
					Id:         "id-2",
					SequenceNo: 2,
					ParentHash: first.Hash,
					Hash:       second.Hash,
				},
				{
					Id:         "id-3",
					SequenceNo: 3,
					ParentHash: second.Hash,
					Hash:       third.Hash,
				},
				{
					Id:         "id-4",
					SequenceNo: 4,
					ParentHash: fourth.Hash,
				},
			},
			want: map[string]error{
				first.Hash:  service.InvalidChainError("Sequence 0 and 1 out of sync\n"),
				second.Hash: service.InvalidChainError("Sequence 1 and 2 out of sync\n"),
				third.Hash:  service.InvalidChainError("Sequence 2 and 3 out of sync\n"),
			},
		},

		"returns repository error": {
			beginChain: 1,
			offset:     4,
			chain:      nil,
			repoErr:    databaseErr,
			want:       nil,
			wantErr:    databaseErr,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := audit.NewMockRepository(t)

			t.Cleanup(func() {
				repo.AssertExpectations(t)
			})

			repo.On(
				"GetChainByOffset",
				ctx,
				test.beginChain,
				test.offset,
			).
				Return(test.chain, test.repoErr).
				Once()

			actual, err := New(repo).VerifyChainByOffset(
				ctx,
				test.beginChain,
				test.offset,
			)

			assert.Equal(t, test.want, actual)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
			} else {
				assert.NoError(t, err)
			}
		})
	}
}
