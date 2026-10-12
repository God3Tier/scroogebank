package main

import (
	"context"
	"errors"
	"testing"

	"auditor/src/internal/models"
	auditSvc "auditor/src/internal/service/audit"
	corruptedLogSvc "auditor/src/internal/service/corrupted_logs"

	"github.com/stretchr/testify/assert"
)

func TestVerify(t *testing.T) {
	ctx := context.Background()
	databaseErr := errors.New("database unavailable")
	finding := models.ChainFinding{Entry: models.AuditLog{Id: "id-3", SequenceNo: 3}, Reason: models.HashMismatch}

	tests := map[string]struct {
		problems  []models.ChainFinding
		verifyErr error
		isNew     bool
		recordErr error
		want      int
	}{
		"exits 0 for an untouched chain": {want: exitOK},
		"exits 2 for a new finding": {
			problems: []models.ChainFinding{finding}, isNew: true, want: exitNewFindings,
		},
		"exits 0 for a finding recorded on an earlier run": {
			problems: []models.ChainFinding{finding}, isNew: false, want: exitOK,
		},
		"exits 1 when the chain cannot be checked": {verifyErr: databaseErr, want: exitFailed},
		"exits 1 when a finding cannot be recorded": {
			problems: []models.ChainFinding{finding}, recordErr: databaseErr, want: exitFailed,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			chain := auditSvc.NewMockService(t)
			chain.EXPECT().VerifyChain(ctx).Return(5, test.problems, test.verifyErr).Once()
			findings := corruptedLogSvc.NewMockService(t)
			for _, problem := range test.problems {
				findings.EXPECT().RecordFinding(ctx, problem).Return(test.isNew, test.recordErr).Once()
			}

			assert.Equal(t, test.want, verify(ctx, chain, findings))
		})
	}
}
