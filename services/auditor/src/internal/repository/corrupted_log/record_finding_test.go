package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestRecordFinding(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "insert_corrupted_log.sql")
	repository := impl{db: db}
	entry := models.AuditLog{
		ID:            "00000000-0000-4000-8000-000000000301",
		SequenceNo:    3,
		EventType:     "CRUD",
		Action:        "UPDATE",
		SourceService: models.CRMServiceType,
		ActorID:       "agent-1",
		Timestamp:     time.Date(2026, 10, 12, 7, 0, 0, 0, time.UTC),
		Payload:       `{}`,
		ParentHash:    "parent-hash",
		Hash:          "entry-hash",
	}
	hashMismatch := models.ChainFinding{Entry: entry, Reason: models.HashMismatch}

	recorded, err := repository.RecordFinding(context.Background(), hashMismatch)
	require.NoError(t, err)
	assert.True(t, recorded, "first time")

	recorded, err = repository.RecordFinding(context.Background(), hashMismatch)
	require.NoError(t, err)
	assert.False(t, recorded, "found again on a later run")

	recorded, err = repository.RecordFinding(context.Background(),
		models.ChainFinding{Entry: entry, Reason: models.SequenceGap})
	require.NoError(t, err)
	assert.True(t, recorded, "another reason for the same row")

	findings, err := repository.GetCorruptedLogs(context.Background())
	require.NoError(t, err)
	require.Len(t, findings, 2)
	reasons := []models.FindingReason{}
	for _, finding := range findings {
		assert.Equal(t, entry.ID, finding.AuditLogID)
		assert.Equal(t, entry, finding.Payload)
		assert.WithinDuration(t, time.Now(), finding.DateOfDetect, 5*time.Second)
		reasons = append(reasons, finding.Reason)
	}
	assert.ElementsMatch(t, []models.FindingReason{models.HashMismatch, models.SequenceGap}, reasons)
}
