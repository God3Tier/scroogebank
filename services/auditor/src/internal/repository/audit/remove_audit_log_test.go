package audit

import (
	"auditor/src/internal/models"
	"context"
	"database/sql"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type storedAuditLink struct {
	sequence int64
	hash     string
	parent   string
}

func TestRemoveAuditLog(t *testing.T) {
	const (
		headHash   = "remove-hash-2"
		middleHash = "remove-hash-7"
		tailHash   = "remove-hash-11"
	)

	originalChain := []storedAuditLink{
		{sequence: 2, hash: headHash, parent: ""},
		{sequence: 7, hash: middleHash, parent: headHash},
		{sequence: 11, hash: tailHash, parent: middleHash},
	}
	tests := map[string]struct {
		fixture     string
		hash        string
		want        []storedAuditLink
		wantRemoved models.AuditLog
		wantErr     error
	}{
		"removes the head and makes its successor the new head": {
			fixture: "remove_audit_log_chain.sql",
			hash:    headHash,
			want: []storedAuditLink{
				{sequence: 7, hash: middleHash, parent: ""},
				{sequence: 11, hash: tailHash, parent: middleHash},
			},
			wantRemoved: models.AuditLog{
				Id: "00000000-0000-0000-0000-000000000601", SequenceNo: 2,
				EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorId: "actor-2", Payload: "{}", ParentHash: "", Hash: headHash,
			},
		},
		"removes a middle log and reconnects its successor": {
			fixture: "remove_audit_log_chain.sql",
			hash:    middleHash,
			want: []storedAuditLink{
				{sequence: 2, hash: headHash, parent: ""},
				{sequence: 11, hash: tailHash, parent: headHash},
			},
			wantRemoved: models.AuditLog{
				Id: "00000000-0000-0000-0000-000000000602", SequenceNo: 7,
				EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorId: "actor-7", Payload: "{}", ParentHash: headHash, Hash: middleHash,
			},
		},
		"removes the tail without changing the other links": {
			fixture: "remove_audit_log_chain.sql",
			hash:    tailHash,
			want: []storedAuditLink{
				{sequence: 2, hash: headHash, parent: ""},
				{sequence: 7, hash: middleHash, parent: headHash},
			},
			wantRemoved: models.AuditLog{
				Id: "00000000-0000-0000-0000-000000000603", SequenceNo: 11,
				EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorId: "actor-11", Payload: "{}", ParentHash: middleHash, Hash: tailHash,
			},
		},
		"removes the only log": {
			fixture: "remove_audit_log_single.sql",
			hash:    "remove-only-hash",
			want:    []storedAuditLink{},
			wantRemoved: models.AuditLog{
				Id: "00000000-0000-0000-0000-000000000604", SequenceNo: 15,
				EventType: "AUTH", Action: "LOGIN_FAILED", SourceService: models.AuditService,
				ActorId: "actor-only", Payload: "{}", ParentHash: "", Hash: "remove-only-hash",
			},
		},
		"returns no rows for an unknown hash": {
			fixture: "remove_audit_log_chain.sql",
			hash:    "unknown-hash",
			want:    originalChain,
			wantErr: sql.ErrNoRows,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)

			removed, err := New(db).RemoveAuditLog(context.Background(), test.hash)
			if test.wantErr != nil {
				require.ErrorIs(t, err, test.wantErr)
				assert.Empty(t, removed)
			} else {
				require.NoError(t, err)
				assert.Equal(t, test.wantRemoved.Id, removed.Id)
				assert.Equal(t, test.wantRemoved.SequenceNo, removed.SequenceNo)
				assert.Equal(t, test.wantRemoved.EventID, removed.EventID)
				assert.Equal(t, test.wantRemoved.EventType, removed.EventType)
				assert.Equal(t, test.wantRemoved.Action, removed.Action)
				assert.Equal(t, test.wantRemoved.SourceService, removed.SourceService)
				assert.Equal(t, test.wantRemoved.ActorId, removed.ActorId)
				assert.NotZero(t, removed.Timestamp)
				assert.Equal(t, test.wantRemoved.Payload, removed.Payload)
				assert.Equal(t, test.wantRemoved.ParentHash, removed.ParentHash)
				assert.Equal(t, test.wantRemoved.Hash, removed.Hash)
			}

			actual := readAuditLinks(t, db)
			assert.Equal(t, test.want, actual)
			assertValidLinks(t, actual)
		})
	}
}

func readAuditLinks(t *testing.T, db *sql.DB) []storedAuditLink {
	t.Helper()
	rows, err := db.Query(`SELECT sequence_no, hash, parent_hash FROM audit_log ORDER BY sequence_no`)
	require.NoError(t, err)
	defer rows.Close()

	actual := make([]storedAuditLink, 0)
	for rows.Next() {
		var entry storedAuditLink
		require.NoError(t, rows.Scan(&entry.sequence, &entry.hash, &entry.parent))
		actual = append(actual, entry)
	}
	require.NoError(t, rows.Err())
	return actual
}

func assertValidLinks(t *testing.T, chain []storedAuditLink) {
	t.Helper()
	if len(chain) == 0 {
		return
	}
	assert.Empty(t, chain[0].parent, "first remaining log must be the head")
	for index := 1; index < len(chain); index++ {
		assert.Equal(t, chain[index-1].hash, chain[index].parent,
			"sequence %d must point to the previous remaining log", chain[index].sequence)
	}
}
