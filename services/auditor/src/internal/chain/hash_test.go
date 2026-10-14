package chain

import (
	"testing"
	"time"

	"auditor/src/internal/models"
	"github.com/stretchr/testify/assert"
)

func sampleEntry() models.AuditLog {
	return models.AuditLog{
		ID:            "00000000-0000-4000-8000-000000000001",
		SequenceNo:    1,
		EventID:       "evt-1",
		EventType:     "CRUD",
		Action:        "UPDATE",
		SourceService: models.CRMServiceType,
		ActorID:       "agent-1",
		Timestamp:     time.Date(2026, 10, 12, 7, 4, 5, 123456000, time.UTC),
		Payload:       `{"client_id":"c-1","status_code":200}`,
	}
}

// The expected hash was computed outside Go, from the encoding ADR-0022
// decision 1 describes:
// 14:audit-chain-v1,36:00000000-0000-4000-8000-000000000001,1:1,5:evt-1,4:CRUD,6:UPDATE,3:CRM,7:agent-1,27:2026-10-12T07:04:05.123456Z,37:{"client_id":"c-1","status_code":200},0:,
func TestHashMatchesTheDocumentedEncoding(t *testing.T) {
	assert.Equal(t, "9697ee3deb11106f41ab4b7060e65cb9a00991c92790a449e4517d2f0ab1a4b3", Hash(sampleEntry()))
}

func TestHashCoversEveryField(t *testing.T) {
	changes := map[string]func(*models.AuditLog){
		"id":             func(e *models.AuditLog) { e.ID = "00000000-0000-4000-8000-000000000002" },
		"sequence_no":    func(e *models.AuditLog) { e.SequenceNo = 2 },
		"event_id":       func(e *models.AuditLog) { e.EventID = "evt-2" },
		"event_type":     func(e *models.AuditLog) { e.EventType = "AUTH" },
		"action":         func(e *models.AuditLog) { e.Action = "DELETE" },
		"source_service": func(e *models.AuditLog) { e.SourceService = models.UserManagementServiceType },
		"actor_id":       func(e *models.AuditLog) { e.ActorID = "agent-2" },
		"timestamp":      func(e *models.AuditLog) { e.Timestamp = e.Timestamp.Add(time.Microsecond) },
		"payload":        func(e *models.AuditLog) { e.Payload = `{"client_id":"c-2","status_code":200}` },
		"parent_hash":    func(e *models.AuditLog) { e.ParentHash = "parent" },
	}
	original := Hash(sampleEntry())

	for field, change := range changes {
		t.Run(field, func(t *testing.T) {
			entry := sampleEntry()
			change(&entry)
			assert.NotEqual(t, original, Hash(entry))
		})
	}
}

func TestHashKeepsFieldBoundaries(t *testing.T) {
	// Joined without lengths, both would read "CRUDUPDATE".
	first, second := sampleEntry(), sampleEntry()
	first.EventType, first.Action = "CRUD", "UPDATE"
	second.EventType, second.Action = "CRUDU", "PDATE"

	assert.NotEqual(t, Hash(first), Hash(second))
}

func TestHashIgnoresTheStoredHashAndTimeZone(t *testing.T) {
	entry := sampleEntry()
	original := Hash(entry)

	entry.Hash = "anything"
	entry.Timestamp = entry.Timestamp.In(time.FixedZone("SGT", 8*60*60))

	assert.Equal(t, original, Hash(entry))
}

func TestNowIsUTCAtMicrosecondPrecision(t *testing.T) {
	now := Now()

	assert.Equal(t, time.UTC, now.Location())
	assert.Zero(t, now.Nanosecond()%1000)
}
