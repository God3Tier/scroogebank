package client

import (
	"context"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestVerifyClientIdentity(t *testing.T) {
	const id = "00000000-0000-0000-0000-000000000101"
	tests := map[string]struct {
		id      string
		deleted bool
		wantErr error
	}{
		"marks existing client verified":         {id: id},
		"returns not found for unknown client":   {id: "00000000-0000-0000-0000-000000000199", wantErr: clienterrors.ErrClientNotFound},
		"returns not found for a deleted client": {id: id, deleted: true, wantErr: clienterrors.ErrClientNotFound},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db, repo, _ := newTestRepository(t)
			loadTestFixture(t, db, "client_empty.sql")
			seedClient(t, db, repo, id, "alice-101@example.test", "+6560000101")
			if test.deleted {
				markDeleted(t, db, id)
			}
			err := repo.VerifyClientIdentity(context.Background(), test.id)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			var status string
			require.NoError(t, db.QueryRow(`SELECT verification_status FROM clients WHERE client_id = $1`, test.id).Scan(&status))
			assert.Equal(t, "verified", status)
		})
	}
}
