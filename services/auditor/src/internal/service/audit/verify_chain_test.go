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
			ID:            fmt.Sprintf("id-%d", sequence),
			SequenceNo:    sequence,
			EventType:     "AUTH",
			Action:        "LOGIN_FAILED",
			SourceService: models.AuditService,
			ActorID:       fmt.Sprintf("actor-%d", sequence),
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
		entries int
		// anchored is the sequence number of the untouched entry the anchor
		// names, or 0 for no anchor.
		anchored     int64
		tamper       func([]models.AuditLog) []models.AuditLog
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
			entries: 5,
			tamper: func(entries []models.AuditLog) []models.AuditLog {
				entries[2].ParentHash = "forged"
				return entries
			},
			wantPages:    []int64{0, 2, 4},
			wantFindings: []models.FindingReason{models.HashMismatch, models.ParentMismatch},
			wantAt:       []int64{3, 3},
		},
		"checks the newest entry": {
			entries: 5,
			tamper: func(entries []models.AuditLog) []models.AuditLog {
				entries[4].Action = "LOGIN"
				return entries
			},
			wantPages:    []int64{0, 2, 4},
			wantFindings: []models.FindingReason{models.HashMismatch},
			wantAt:       []int64{5},
		},
		"checks the anchored entry on a later page": {
			entries:  5,
			anchored: 4,
			// Rewritten from entry 4 on, with every hash recomputed.
			tamper: func(entries []models.AuditLog) []models.AuditLog {
				entries[3].Action = "LOGIN"
				for i := 3; i < len(entries); i++ {
					entries[i].ParentHash = entries[i-1].Hash
					entries[i].Hash = chain.Hash(entries[i])
				}
				return entries
			},
			wantPages:    []int64{0, 2, 4},
			wantFindings: []models.FindingReason{models.AnchorMismatch},
			wantAt:       []int64{4},
		},
		"reports an anchored entry that is gone after the last page": {
			entries:      5,
			anchored:     5,
			tamper:       func(entries []models.AuditLog) []models.AuditLog { return entries[:3] },
			wantPages:    []int64{0, 2},
			wantFindings: []models.FindingReason{models.AnchorMismatch},
			wantAt:       []int64{5},
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			entries := linkedEntries(test.entries)
			var anchor *models.ChainAnchor
			if test.anchored > 0 {
				named := entries[test.anchored-1]
				anchor = &models.ChainAnchor{SequenceNo: named.SequenceNo, ID: named.ID, Hash: named.Hash}
			}
			if test.tamper != nil {
				entries = test.tamper(entries)
			}
			repo := audit.NewMockRepository(t)
			for _, after := range test.wantPages {
				end := min(int(after)+2, len(entries))
				repo.EXPECT().GetChainPage(ctx, after, 2).Return(entries[after:end], nil).Once()
			}

			report, err := impl{repo: repo, pageSize: 2}.VerifyChain(ctx, anchor)

			require.NoError(t, err)
			assert.Equal(t, int64(len(entries)), report.Checked)
			if len(entries) == 0 {
				assert.Nil(t, report.Head)
			} else {
				require.NotNil(t, report.Head)
				assert.Equal(t, entries[len(entries)-1], *report.Head)
			}
			var reasons []models.FindingReason
			var at []int64
			for _, finding := range report.Findings {
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

	report, err := impl{repo: repo, pageSize: 2}.VerifyChain(ctx, nil)

	require.ErrorIs(t, err, databaseErr)
	assert.Equal(t, models.ChainReport{}, report)
}
