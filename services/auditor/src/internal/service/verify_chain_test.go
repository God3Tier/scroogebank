package service

import (
	"auditor/src/internal/models"
	"auditor/src/internal/pkg"
	"auditor/src/internal/repository/audit"
	"context"
	"errors"
	"strconv"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestVerifyChain(t *testing.T) {
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
		chain   []models.AuditLog
		repoErr error
		want    map[int]error
		wantErr error
	}{
		"returns no mismatch for empty chain": {
			chain: []models.AuditLog{},
			want:  map[int]error{},
		},

		"returns no mismatch for one log": {
			chain: []models.AuditLog{first},
			want:  map[int]error{},
		},

		"returns no mismatch for valid chain": {
			chain: []models.AuditLog{
				first,
				second,
				third,
				fourth,
				fifth,
			},
			want: map[int]error{},
		},

		"returns one mismatch": {
			chain: []models.AuditLog{
				first,
				{
					Id:         "id-2",
					SequenceNo: 2,
					ParentHash: first.Hash,
				},
				third,
			},
			want: map[int]error{
				1: InvalidChainError("Sequence 1 and 2 out of sync\n"),
			},
		},

		"returns multiple consecutive mismatches": {
			chain: []models.AuditLog{
				first,
				{
					Id:         "id-2",
					SequenceNo: 2,
					ParentHash: first.Hash,
				},
				{
					Id:         "id-3",
					SequenceNo: 3,
					ParentHash: second.Hash,
				},
				fourth,
			},
			want: map[int]error{
				1: InvalidChainError("Sequence 1 and 2 out of sync\n"),
				2: InvalidChainError("Sequence 2 and 3 out of sync\n"),
			},
		},

		"returns multiple non-consecutive mismatches": {
			chain: []models.AuditLog{
				first,
				{
					Id:         "id-2",
					SequenceNo: 2,
					ParentHash: first.Hash,
				},
				third,
				{
					Id:         "id-4",
					SequenceNo: 4,
					ParentHash: third.Hash,
				},
				fifth,
			},
			want: map[int]error{
				1: InvalidChainError("Sequence 1 and 2 out of sync\n"),
				3: InvalidChainError("Sequence 3 and 4 out of sync\n"),
			},
		},

		"returns all mismatches": {
			chain: []models.AuditLog{
				{
					Id:         "id-1",
					SequenceNo: 1,
				},
				{
					Id:         "id-2",
					SequenceNo: 2,
					ParentHash: first.Hash,
				},
				{
					Id:         "id-3",
					SequenceNo: 3,
					ParentHash: second.Hash,
				},
				{
					Id:         "id-4",
					SequenceNo: 4,
					ParentHash: fourth.Hash,
				},
			},
			want: map[int]error{
				0: InvalidChainError("Sequence 0 and 1 out of sync\n"),
				1: InvalidChainError("Sequence 1 and 2 out of sync\n"),
				2: InvalidChainError("Sequence 2 and 3 out of sync\n"),
			},
		},

		"returns repository error": {
			chain:   nil,
			repoErr: databaseErr,
			want:    nil,
			wantErr: databaseErr,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := audit.NewMockRepository(t)

			t.Cleanup(func() {
				repo.AssertExpectations(t)
			})

			repo.On("GetChain", ctx).
				Return(test.chain, test.repoErr).
				Once()

			actual, err := New(repo).VerifyChain(ctx)

			assert.Equal(t, test.want, actual)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
			} else {
				assert.NoError(t, err)
			}
		})
	}
}

func hashAuditLog(log models.AuditLog) string {
	rawString := log.Id + strconv.FormatInt(log.SequenceNo, 10) + log.EventType + log.Action + string(log.SourceService) + log.ActorId + log.Timestamp.Format(time.RFC3339) + log.Payload
	return pkg.Hash(rawString)
}
