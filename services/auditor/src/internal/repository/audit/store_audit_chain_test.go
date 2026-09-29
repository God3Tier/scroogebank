package audit

import (
	"context"
	"testing"

	"auditor/src/internal/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestStoreAuditChain(t *testing.T) {
	tests := map[string]struct {
		fixture      string
		request      models.AuditLogInput
		wantSequence int64
		wantParent   string
	}{
		"stores first log without parent": {
			fixture: "store_audit_chain_empty.sql",
			request: models.AuditLogInput{
				EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorId: "actor-first", Payload: `{}`,
			},
			wantSequence: 1,
		},
		"uses previous log hash as parent": {
			fixture: "store_audit_chain_previous.sql",
			request: models.AuditLogInput{
				EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorId: "actor-next", Payload: `{}`,
			},
			wantSequence: 4,
			wantParent:   "chain-hash-3",
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)

			err := New(db).StoreAuditChain(context.Background(), test.request)

			require.NoError(t, err)
			var sequence int64
			var parent string
			err = db.QueryRowContext(context.Background(),
				`SELECT sequence_no, parent_hash FROM audit_log ORDER BY sequence_no DESC LIMIT 1`,
			).Scan(&sequence, &parent)
			require.NoError(t, err)
			assert.Equal(t, test.wantSequence, sequence)
			assert.Equal(t, test.wantParent, parent)
		})
	}
}

func TestStoreAuditChain_DeduplicatesSourceEventId(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "store_audit_chain_empty.sql")
	request := models.AuditLogInput{
		EventId:   "70000000-0000-4000-8000-000000000001",
		EventType: "EVENT_ALERT", Action: "GENERATED",
		SourceService: models.EventAlertServiceType,
		ActorId:       "event-alert-service", Payload: `{}`,
	}

	repository := New(db)
	require.NoError(t, repository.StoreAuditChain(context.Background(), request))
	require.Error(t, repository.StoreAuditChain(context.Background(), request))

	var count int
	require.NoError(t, db.QueryRowContext(context.Background(),
		`SELECT COUNT(*) FROM audit_log WHERE id = $1`, request.EventId).Scan(&count))
	assert.Equal(t, 1, count)
}
