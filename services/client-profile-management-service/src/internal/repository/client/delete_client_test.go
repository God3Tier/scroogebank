package client

import (
	"context"
	"database/sql"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestDeleteClient(t *testing.T) {
	const id = "00000000-0000-0000-0000-000000000101"
	tests := map[string]struct {
		id      string
		deleted bool
		wantErr error
	}{
		"soft-deletes existing client":           {id: id},
		"returns not found for unknown client":   {id: "00000000-0000-0000-0000-000000000199", wantErr: clienterrors.ErrClientNotFound},
		"returns not found for a deleted client": {id: id, deleted: true, wantErr: clienterrors.ErrClientNotFound},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db, repo, protector := newTestRepository(t)
			loadTestFixture(t, db, "client_empty.sql")
			seedClient(t, db, repo, id, "alice-101@example.test", "+6560000101")
			if test.deleted {
				markDeleted(t, db, id)
			}
			err := repo.DeleteClient(context.Background(), test.id)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			// The row is kept, marked deleted. Deleting is not erasure, so the
			// client's data key stays too (ADR-0036).
			var deletedAt sql.NullTime
			require.NoError(t, db.QueryRow(`SELECT deleted_at FROM clients WHERE client_id = $1`, test.id).Scan(&deletedAt))
			assert.True(t, deletedAt.Valid)
			assert.True(t, protector.HasKey(test.id))
		})
	}
}
