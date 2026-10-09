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
		wantEventID  string
		wantErr      error
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
		"finds the linked-list head with gaps in sequence numbers": {
			fixture: "store_audit_chain_sparse.sql",
			request: models.AuditLogInput{
				EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorId: "actor-after-gap", Payload: `{}`,
			},
			wantSequence: 8,
			wantParent:   "sparse-head-hash",
		},
		"stores the event_id alongside the log": {
			fixture: "store_audit_chain_empty.sql",
			request: models.AuditLogInput{
				EventID: "evt-new", EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorId: "actor-first", Payload: `{}`,
			},
			wantSequence: 1,
			wantEventID:  "evt-new",
		},
		"rejects a redelivered event_id without writing a new entry": {
			fixture: "store_audit_chain_duplicate.sql",
			request: models.AuditLogInput{
				EventID: "evt-existing", EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorId: "actor-retry", Payload: `{}`,
			},
			wantSequence: 1, // unchanged: the duplicate must not be inserted
			wantEventID:  "evt-existing",
			wantErr:      DuplicateEventError,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)

			err := New(db).StoreAuditChain(context.Background(), test.request)

			if test.wantErr != nil {
				require.ErrorIs(t, err, test.wantErr)
			} else {
				require.NoError(t, err)
			}

			var sequence int64
			var parent, eventID string
			err = db.QueryRowContext(context.Background(),
				`SELECT sequence_no, parent_hash, COALESCE(event_id, '') FROM audit_log ORDER BY sequence_no DESC LIMIT 1`,
			).Scan(&sequence, &parent, &eventID)
			require.NoError(t, err)
			assert.Equal(t, test.wantSequence, sequence)
			assert.Equal(t, test.wantParent, parent)
			assert.Equal(t, test.wantEventID, eventID)
		})
	}
}

func TestStoreAuditChain_DeduplicatesSourceEventId(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "store_audit_chain_empty.sql")
	request := models.AuditLogInput{
		EventID:   "70000000-0000-4000-8000-000000000001",
		EventType: "EVENT_ALERT", Action: "GENERATED",
		SourceService: models.EventAlertServiceType,
		ActorId:       "event-alert-service", Payload: `{}`,
	}

	repository := New(db)
	require.NoError(t, repository.StoreAuditChain(context.Background(), request))
	require.ErrorIs(t, repository.StoreAuditChain(context.Background(), request), DuplicateEventError)

	var count int
	require.NoError(t, db.QueryRowContext(context.Background(),
		`SELECT COUNT(*) FROM audit_log WHERE event_id = $1`, request.EventID).Scan(&count))
	assert.Equal(t, 1, count)
}
