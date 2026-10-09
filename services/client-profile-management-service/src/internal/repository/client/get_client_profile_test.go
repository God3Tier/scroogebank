package client

import (
	"context"
	"testing"

	"client-profile-management-service/src/internal/encryption"
	"client-profile-management-service/src/internal/encryption/encryptiontest"
	clienterrors "client-profile-management-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetClientProfile(t *testing.T) {
	const id = "00000000-0000-0000-0000-000000000101"
	tests := map[string]struct {
		id      string
		deleted bool
		wantErr error
	}{
		"returns the decrypted profile":     {id: id},
		"maps missing profile to not found": {id: "00000000-0000-0000-0000-000000000199", wantErr: clienterrors.ErrClientNotFound},
		"maps deleted profile to not found": {id: id, deleted: true, wantErr: clienterrors.ErrClientNotFound},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db, repo, _ := newTestRepository(t)
			loadTestFixture(t, db, "client_empty.sql")
			seeded := seedClient(t, db, repo, id, "alice-101@example.test", "+6560000101")
			if test.deleted {
				markDeleted(t, db, id)
			}
			got, err := repo.GetClientProfile(context.Background(), test.id)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, test.id, got.ClientID.String())
			assert.Equal(t, "Alice", got.FirstName)
			assert.Equal(t, "alice-101@example.test", got.Email)
			assert.Equal(t, seeded.DateOfBirth, got.DateOfBirth)
			assert.Equal(t, seeded.CreatedByAgentID, got.CreatedByAgentID)
		})
	}
}

func TestGetClientProfileWithoutItsDataKey(t *testing.T) {
	const id = "00000000-0000-0000-0000-000000000101"
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, id, "alice-101@example.test", "+6560000101")
	// A protector that never stored this client's key, as after erasure.
	withoutKey := clientRepository{db: db, protector: encryptiontest.New()}

	_, err := withoutKey.GetClientProfile(context.Background(), id)
	assert.ErrorIs(t, err, encryption.ErrKeyNotFound)
}
