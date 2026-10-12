package chain

import (
	"fmt"
	"testing"
	"time"

	"auditor/src/internal/models"
	"github.com/stretchr/testify/assert"
)

// validChain returns n linked entries with correct hashes.
func validChain(n int) []models.AuditLog {
	entries := make([]models.AuditLog, 0, n)
	parent := ""
	for sequence := int64(1); sequence <= int64(n); sequence++ {
		entry := models.AuditLog{
			Id:            fmt.Sprintf("00000000-0000-4000-8000-%012d", sequence),
			SequenceNo:    sequence,
			EventType:     "CRUD",
			Action:        "UPDATE",
			SourceService: models.CRMServiceType,
			ActorId:       "agent-1",
			Timestamp:     time.Date(2026, 10, 12, 7, 0, int(sequence), 0, time.UTC),
			Payload:       `{}`,
			ParentHash:    parent,
		}
		entry.Hash = Hash(entry)
		parent = entry.Hash
		entries = append(entries, entry)
	}
	return entries
}

type problem struct {
	sequence int64
	reason   models.FindingReason
}

func TestChecker(t *testing.T) {
	tests := map[string]struct {
		tamper func([]models.AuditLog) []models.AuditLog
		want   []problem
	}{
		"finds nothing in an untouched chain": {
			tamper: func(entries []models.AuditLog) []models.AuditLog { return entries },
		},
		"finds a row edited without a new hash": {
			tamper: func(entries []models.AuditLog) []models.AuditLog {
				entries[2].Action = "DELETE"
				return entries
			},
			want: []problem{{3, models.HashMismatch}},
		},
		"finds a row edited and given a new hash, at the next row": {
			tamper: func(entries []models.AuditLog) []models.AuditLog {
				entries[2].Action = "DELETE"
				entries[2].Hash = Hash(entries[2])
				return entries
			},
			want: []problem{{4, models.ParentMismatch}},
		},
		"finds a deleted middle row": {
			tamper: func(entries []models.AuditLog) []models.AuditLog {
				return append(entries[:2], entries[3:]...)
			},
			want: []problem{{4, models.ParentMismatch}, {4, models.SequenceGap}},
		},
		"finds a deleted first row": {
			tamper: func(entries []models.AuditLog) []models.AuditLog { return entries[1:] },
			want:   []problem{{2, models.ParentMismatch}, {2, models.SequenceGap}},
		},
		"finds an edited newest row": {
			tamper: func(entries []models.AuditLog) []models.AuditLog {
				entries[4].Payload = `{"edited":true}`
				return entries
			},
			want: []problem{{5, models.HashMismatch}},
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			var checker Checker
			var got []problem
			for _, entry := range test.tamper(validChain(5)) {
				for _, finding := range checker.Check(entry) {
					got = append(got, problem{finding.Entry.SequenceNo, finding.Reason})
				}
			}
			assert.Equal(t, test.want, got)
		})
	}
}
