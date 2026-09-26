package client

import (
	"context"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetClientProfile(t *testing.T) {
	tests := map[string]struct {
		id      string
		fixture string
		wantErr error
	}{
		"returns persisted profile":         {id: "00000000-0000-0000-0000-000000000101", fixture: "client_lookup.sql"},
		"maps missing profile to not found": {id: "00000000-0000-0000-0000-000000000199", fixture: "client_empty.sql", wantErr: clienterrors.ErrClientNotFound},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)
			got, err := NewClientRepository(db).GetClientProfile(context.Background(), test.id)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, test.id, got.ClientID.String())
			assert.Equal(t, "Alice", got.FirstName)
			assert.Equal(t, "alice-101@example.test", got.Email)
		})
	}
}
