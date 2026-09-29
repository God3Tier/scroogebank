package audit

import (
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
		fixture string
		hash    string
		want    []storedAuditLink
		wantErr error
	}{
		"removes the head and makes its successor the new head": {
			fixture: "remove_audit_log_chain.sql",
			hash:    headHash,
			want: []storedAuditLink{
				{sequence: 7, hash: middleHash, parent: ""},
				{sequence: 11, hash: tailHash, parent: middleHash},
			},
		},
		"removes a middle log and reconnects its successor": {
			fixture: "remove_audit_log_chain.sql",
			hash:    middleHash,
			want: []storedAuditLink{
				{sequence: 2, hash: headHash, parent: ""},
				{sequence: 11, hash: tailHash, parent: headHash},
			},
		},
		"removes the tail without changing the other links": {
			fixture: "remove_audit_log_chain.sql",
			hash:    tailHash,
			want: []storedAuditLink{
				{sequence: 2, hash: headHash, parent: ""},
				{sequence: 7, hash: middleHash, parent: headHash},
			},
		},
		"removes the only log": {
			fixture: "remove_audit_log_single.sql",
			hash:    "remove-only-hash",
			want:    []storedAuditLink{},
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

			err := New(db).RemoveAuditLog(context.Background(), test.hash)
			if test.wantErr != nil {
				require.ErrorIs(t, err, test.wantErr)
			} else {
				require.NoError(t, err)
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
