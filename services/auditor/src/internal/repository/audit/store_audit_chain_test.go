package audit

import (
	"context"
	"fmt"
	"testing"

	"auditor/src/internal/chain"
	"auditor/src/internal/models"
	"github.com/lib/pq"
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
				ActorID: "actor-first", Payload: `{}`,
			},
			wantSequence: 1,
		},
		"uses previous log hash as parent": {
			fixture: "store_audit_chain_previous.sql",
			request: models.AuditLogInput{
				EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorID: "actor-next", Payload: `{}`,
			},
			wantSequence: 4,
			wantParent:   "chain-hash-3",
		},
		"appends after the highest sequence number": {
			fixture: "store_audit_chain_sparse.sql",
			request: models.AuditLogInput{
				EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorID: "actor-after-gap", Payload: `{}`,
			},
			wantSequence: 8,
			wantParent:   "sparse-head-hash",
		},
		"stores the event_id alongside the log": {
			fixture: "store_audit_chain_empty.sql",
			request: models.AuditLogInput{
				EventID: "evt-new", EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorID: "actor-first", Payload: `{}`,
			},
			wantSequence: 1,
			wantEventID:  "evt-new",
		},
		"rejects a redelivered event_id without writing a new entry": {
			fixture: "store_audit_chain_duplicate.sql",
			request: models.AuditLogInput{
				EventID: "evt-existing", EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorID: "actor-retry", Payload: `{}`,
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
		ActorID:       "event-alert-service", Payload: `{}`,
	}

	repository := New(db)
	require.NoError(t, repository.StoreAuditChain(context.Background(), request))
	require.ErrorIs(t, repository.StoreAuditChain(context.Background(), request), DuplicateEventError)

	var count int
	require.NoError(t, db.QueryRowContext(context.Background(),
		`SELECT COUNT(*) FROM audit_log WHERE event_id = $1`, request.EventID).Scan(&count))
	assert.Equal(t, 1, count)
}

// The hash is computed over the entry exactly as Postgres stores and returns
// it, so recomputing it from the stored row gives the same hash. jsonb would
// reorder these keys and add spaces.
func TestStoreAuditChain_StoredEntryMatchesItsHash(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "store_audit_chain_empty.sql")
	payload := `{"status_code":200, "agent_id":"AGT-1","client_id":"c-1"}`

	repository := New(db)
	require.NoError(t, repository.StoreAuditChain(context.Background(), models.AuditLogInput{
		EventID: "evt-hash", EventType: "CRUD", Action: "UPDATE", SourceService: models.CRMServiceType,
		ActorID: "agent-1", Payload: " \n" + payload + "\n",
	}))
	require.NoError(t, repository.StoreAuditChain(context.Background(), models.AuditLogInput{
		EventType: "CRUD", Action: "CREATE", SourceService: models.CRMServiceType,
		ActorID: "agent-1", Payload: `{}`,
	}))

	entries, err := repository.GetChainPage(context.Background(), 0, 10)
	require.NoError(t, err)
	require.Len(t, entries, 2)
	assert.Equal(t, payload, entries[0].Payload)
	assert.Equal(t, "", entries[1].EventID)
	var checker chain.Checker
	for _, entry := range entries {
		assert.Empty(t, checker.Check(entry))
	}
}

func TestStoreAuditChain_ConcurrentWritersKeepOneChain(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "store_audit_chain_empty.sql")
	repository := New(db)

	const writers = 20
	errs := make(chan error, writers)
	for writer := 0; writer < writers; writer++ {
		go func() {
			errs <- repository.StoreAuditChain(context.Background(), models.AuditLogInput{
				EventID: fmt.Sprintf("evt-concurrent-%d", writer), EventType: "CRUD", Action: "UPDATE",
				SourceService: models.CRMServiceType, ActorID: "agent-1", Payload: `{}`,
			})
		}()
	}
	for writer := 0; writer < writers; writer++ {
		require.NoError(t, <-errs)
	}

	entries, err := repository.GetChainPage(context.Background(), 0, writers+1)
	require.NoError(t, err)
	require.Len(t, entries, writers)
	var checker chain.Checker
	for _, entry := range entries {
		assert.Empty(t, checker.Check(entry), "entry %d", entry.SequenceNo)
	}
}

// The auditor's role may only insert into and read audit_log
// (ADR-0022 decision 2), and appending needs nothing more.
func TestStoreAuditChain_AuditorRoleCanOnlyAppend(t *testing.T) {
	admin := openTestDB(t)
	loadTestFixture(t, admin, "store_audit_chain_empty.sql")
	auditor := openTestDBAs(t, admin, "auditor_service_repository_test", "auditor_service")

	repository := New(auditor)
	for _, eventID := range []string{"evt-role-1", "evt-role-2"} {
		require.NoError(t, repository.StoreAuditChain(context.Background(), models.AuditLogInput{
			EventID: eventID, EventType: "CRUD", Action: "UPDATE", SourceService: models.CRMServiceType,
			ActorID: "agent-1", Payload: `{}`,
		}))
	}
	_, err := repository.GetChainPage(context.Background(), 0, 10)
	require.NoError(t, err)

	for _, statement := range []string{
		`UPDATE audit_log SET action = 'DELETE'`,
		`DELETE FROM audit_log`,
		`TRUNCATE audit_log`,
	} {
		_, err := auditor.Exec(statement)
		var pqErr *pq.Error
		require.ErrorAs(t, err, &pqErr, statement)
		assert.Equal(t, pq.ErrorCode("42501"), pqErr.Code, "%s: want insufficient_privilege", statement)
	}
}
