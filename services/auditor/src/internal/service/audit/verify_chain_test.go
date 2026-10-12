package audit

import (
	"auditor/src/internal/chain"
	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// linkedEntries returns n linked entries with correct hashes.
func linkedEntries(n int) []models.AuditLog {
	entries := make([]models.AuditLog, 0, n)
	parent := ""
	for sequence := int64(1); sequence <= int64(n); sequence++ {
		entry := models.AuditLog{
			Id:            fmt.Sprintf("id-%d", sequence),
			SequenceNo:    sequence,
			EventType:     "AUTH",
			Action:        "LOGIN_FAILED",
			SourceService: models.AuditService,
			ActorId:       fmt.Sprintf("actor-%d", sequence),
			Timestamp:     time.Date(2026, 9, 15, 5, 0, int(sequence), 0, time.UTC),
			Payload:       `{}`,
			ParentHash:    parent,
		}
		entry.Hash = chain.Hash(entry)
		parent = entry.Hash
		entries = append(entries, entry)
	}
	return entries
}

func TestVerifyChain(t *testing.T) {
	ctx := context.Background()

	tests := map[string]struct {
		entries      int
		tamper       func([]models.AuditLog)
		wantPages    []int64 // the afterSequenceNo of each page read
		wantFindings []models.FindingReason
		wantAt       []int64
	}{
		"reads every page of an untouched chain": {
			entries:   5,
			wantPages: []int64{0, 2, 4},
		},
		"reads one more page when the last one is full": {
			entries:   4,
			wantPages: []int64{0, 2, 4},
		},
		"reads an empty chain": {
			entries:   0,
			wantPages: []int64{0},
		},
		"compares the first entry of a page with the last entry of the page before": {
			entries:      5,
			tamper:       func(entries []models.AuditLog) { entries[2].ParentHash = "forged" },
			wantPages:    []int64{0, 2, 4},
			wantFindings: []models.FindingReason{models.HashMismatch, models.ParentMismatch},
			wantAt:       []int64{3, 3},
		},
		"checks the newest entry": {
			entries:      5,
			tamper:       func(entries []models.AuditLog) { entries[4].Action = "LOGIN" },
			wantPages:    []int64{0, 2, 4},
			wantFindings: []models.FindingReason{models.HashMismatch},
			wantAt:       []int64{5},
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			entries := linkedEntries(test.entries)
			if test.tamper != nil {
				test.tamper(entries)
			}
			repo := audit.NewMockRepository(t)
			for _, after := range test.wantPages {
				end := min(int(after)+2, len(entries))
				repo.EXPECT().GetChainPage(ctx, after, 2).Return(entries[after:end], nil).Once()
			}

			checked, findings, err := impl{repo: repo, pageSize: 2}.VerifyChain(ctx)

			require.NoError(t, err)
			assert.Equal(t, int64(test.entries), checked)
			var reasons []models.FindingReason
			var at []int64
			for _, finding := range findings {
				reasons = append(reasons, finding.Reason)
				at = append(at, finding.Entry.SequenceNo)
			}
			assert.Equal(t, test.wantFindings, reasons)
			assert.Equal(t, test.wantAt, at)
		})
	}
}

func TestVerifyChainReturnsRepositoryErrors(t *testing.T) {
	ctx := context.Background()
	databaseErr := errors.New("database unavailable")
	entries := linkedEntries(2)
	repo := audit.NewMockRepository(t)
	repo.EXPECT().GetChainPage(ctx, int64(0), 2).Return(entries, nil).Once()
	repo.EXPECT().GetChainPage(ctx, int64(2), 2).Return(nil, databaseErr).Once()

	checked, findings, err := impl{repo: repo, pageSize: 2}.VerifyChain(ctx)

	require.ErrorIs(t, err, databaseErr)
	assert.Zero(t, checked)
	assert.Nil(t, findings)
}
