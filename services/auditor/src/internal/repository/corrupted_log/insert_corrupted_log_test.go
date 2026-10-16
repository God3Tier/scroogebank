package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestInsertCorruptedLog(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "insert_corrupted_log.sql")

	input := models.CorruptedAuditLog{
		ID: "00000000-0000-0000-0000-000000000101",
		Payload: models.AuditLog{
			ID:            "audit-101",
			SequenceNo:    101,
			EventID:       "event-101",
			EventType:     "CRUD",
			Action:        "UPDATE_CLIENT",
			SourceService: models.CRMServiceType,
			ActorID:       "agent-1",
			Timestamp:     time.Date(2026, 9, 29, 10, 0, 0, 0, time.UTC),
			Payload:       `{"client":"client-1"}`,
			ParentHash:    "parent-hash",
			Hash:          "entry-hash",
		},
	}

	err := (impl{db: db}).InsertCorruptedLog(context.Background(), input)
	require.NoError(t, err)

	var actual models.CorruptedAuditLog
	var payload []byte
	err = db.QueryRow(`SELECT id, log_type, date_of_detect FROM corrupted_audit_log WHERE id = $1`, input.ID).
		Scan(&actual.ID, &payload, &actual.DateOfDetect)
	require.NoError(t, err)
	assert.Equal(t, input.ID, actual.ID)
	assert.WithinDuration(t, time.Now(), actual.DateOfDetect, 5*time.Second)
	assert.JSONEq(t, `{"ID":"audit-101","SequenceNo":101,"EventID":"event-101","EventType":"CRUD","Action":"UPDATE_CLIENT","SourceService":"CRM","ActorID":"agent-1","Timestamp":"2026-09-29T10:00:00Z","Payload":"{\"client\":\"client-1\"}","ParentHash":"parent-hash","Hash":"entry-hash"}`, string(payload))
}
