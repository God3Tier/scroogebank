package main

import (
	"context"
	"errors"
	"testing"

	"auditor/src/internal/models"
	"auditor/src/internal/repository/anchor"
	auditSvc "auditor/src/internal/service/audit"
	corruptedLogSvc "auditor/src/internal/service/corrupted_logs"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestVerify(t *testing.T) {
	ctx := context.Background()
	databaseErr := errors.New("database unavailable")
	s3Err := errors.New("access denied")
	latest := &models.ChainAnchor{SequenceNo: 4, ID: "id-4", Hash: "hash-4"}
	head := &models.AuditLog{Id: "id-5", SequenceNo: 5, Hash: "hash-5"}
	finding := models.ChainFinding{Entry: models.AuditLog{Id: "id-3", SequenceNo: 3}, Reason: models.HashMismatch}

	tests := map[string]struct {
		latestErr error
		// verifies says whether VerifyChain is reached.
		verifies  bool
		verifyErr error
		problems  []models.ChainFinding
		isNew     bool
		recordErr error
		head      *models.AuditLog
		// writes says whether the head is anchored.
		writes   bool
		writeErr error
		want     int
	}{
		"exits 0 and anchors the head of an untouched chain": {
			verifies: true, head: head, writes: true, want: exitOK,
		},
		"exits 2 for a new finding, and still anchors the head": {
			verifies: true, problems: []models.ChainFinding{finding}, isNew: true,
			head: head, writes: true, want: exitNewFindings,
		},
		"exits 0 for a finding recorded on an earlier run": {
			verifies: true, problems: []models.ChainFinding{finding}, isNew: false,
			head: head, writes: true, want: exitOK,
		},
		"anchors nothing for an empty chain": {
			verifies: true, want: exitOK,
		},
		"exits 1 when the latest anchor cannot be read": {
			latestErr: s3Err, want: exitFailed,
		},
		"exits 1 when the chain cannot be checked": {
			verifies: true, verifyErr: databaseErr, want: exitFailed,
		},
		"exits 1 when a finding cannot be recorded": {
			verifies: true, problems: []models.ChainFinding{finding}, recordErr: databaseErr,
			head: head, want: exitFailed,
		},
		"exits 1 when the head cannot be anchored": {
			verifies: true, head: head, writes: true, writeErr: s3Err, want: exitFailed,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			anchors := anchor.NewMockRepository(t)
			anchors.EXPECT().Latest(ctx).Return(latest, test.latestErr).Once()
			chain := auditSvc.NewMockService(t)
			if test.verifies {
				report := models.ChainReport{Checked: 5, Findings: test.problems, Head: test.head}
				chain.EXPECT().VerifyChain(ctx, latest).Return(report, test.verifyErr).Once()
			}
			findings := corruptedLogSvc.NewMockService(t)
			for _, problem := range test.problems {
				findings.EXPECT().RecordFinding(ctx, problem).Return(test.isNew, test.recordErr).Once()
			}
			if test.writes {
				anchors.EXPECT().Write(ctx, mock.MatchedBy(func(written models.ChainAnchor) bool {
					return written.SequenceNo == head.SequenceNo && written.ID == head.Id &&
						written.Hash == head.Hash && !written.AnchoredAt.IsZero()
				})).Return(test.writeErr).Once()
			}

			assert.Equal(t, test.want, verify(ctx, chain, findings, anchors))
		})
	}
}
