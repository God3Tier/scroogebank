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
	tests := map[string]struct {
		id      string
		fixture string
		wantErr error
	}{
		"soft-deletes existing client":           {id: "00000000-0000-0000-0000-000000000101", fixture: "client_lookup.sql"},
		"returns not found for unknown client":   {id: "00000000-0000-0000-0000-000000000199", fixture: "client_empty.sql", wantErr: clienterrors.ErrClientNotFound},
		"returns not found for a deleted client": {id: "00000000-0000-0000-0000-000000000103", fixture: "client_deleted.sql", wantErr: clienterrors.ErrClientNotFound},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)
			err := NewClientRepository(db).DeleteClient(context.Background(), test.id)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			// The row is kept, marked deleted.
			var deletedAt sql.NullTime
			require.NoError(t, db.QueryRow(`SELECT deleted_at FROM clients WHERE client_id = $1`, test.id).Scan(&deletedAt))
			assert.True(t, deletedAt.Valid)
		})
	}
}
