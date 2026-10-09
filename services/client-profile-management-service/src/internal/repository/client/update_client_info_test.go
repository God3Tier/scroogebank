package client

import (
	"context"
	"database/sql"
	"testing"

	"client-profile-management-service/src/internal/encryption/encryptiontest"
	clienterrors "client-profile-management-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestUpdateClientInformation(t *testing.T) {
	const id = "00000000-0000-0000-0000-000000000101"
	tests := map[string]struct {
		id      string
		deleted bool
		wantErr error
	}{
		"updates existing profile":                {id: id},
		"returns not found for unknown profile":   {id: "00000000-0000-0000-0000-000000000199", wantErr: clienterrors.ErrClientNotFound},
		"returns not found for a deleted profile": {id: id, deleted: true, wantErr: clienterrors.ErrClientNotFound},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db, repo, _ := newTestRepository(t)
			loadTestFixture(t, db, "client_empty.sql")
			profile := seedClient(t, db, repo, id, "alice-101@example.test", "+6560000101")
			if test.deleted {
				markDeleted(t, db, id)
			}
			profile.FirstName = "Updated Alice"
			profile.Email = "Updated-101@example.test"
			profile.PhoneNumber = "+65 6000 0111"
			err := repo.UpdateClientInformation(context.Background(), test.id, profile)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)

			got, err := repo.GetClientProfile(context.Background(), test.id)
			require.NoError(t, err)
			assert.Equal(t, "Updated Alice", got.FirstName)
			assert.Equal(t, "Updated-101@example.test", got.Email, "the stored value is the one entered")

			var emailHash, phoneHash string
			var deletedAt sql.NullTime
			require.NoError(t, db.QueryRow(`SELECT email_hash, phone_number_hash, deleted_at FROM clients WHERE client_id = $1`, test.id).
				Scan(&emailHash, &phoneHash, &deletedAt))
			assert.Equal(t, encryptiontest.Index("updated-101@example.test"), emailHash)
			assert.Equal(t, encryptiontest.Index("+6560000111"), phoneHash)
			assert.False(t, deletedAt.Valid)
		})
	}
}
