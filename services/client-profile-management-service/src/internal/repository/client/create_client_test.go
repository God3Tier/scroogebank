package client

import (
	"context"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCreateClient(t *testing.T) {
	tests := map[string]struct {
		fixture      string
		invalidZip   bool
		duplicate    bool
		reuseDeleted bool
	}{
		"persists a new client":                     {fixture: "client_empty.sql"},
		"rejects duplicate email or phone":          {fixture: "client_duplicate.sql", duplicate: true},
		"returns database constraint error":         {fixture: "client_empty.sql", invalidZip: true},
		"reuses a deleted client's email and phone": {fixture: "client_deleted.sql", reuseDeleted: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)
			profile := validRepositoryClient()
			if test.duplicate {
				profile.Email = "duplicate@example.test"
			}
			if test.reuseDeleted {
				profile.Email = "deleted-103@example.test"
				profile.PhoneNumber = "+6560000103"
			}
			if test.invalidZip {
				profile.PostalCode = "12345678901"
			}
			err := NewClientRepository(db).CreateClient(context.Background(), profile)
			if test.duplicate {
				assert.ErrorIs(t, err, clienterrors.ErrDuplicateClient)
				return
			}
			if test.invalidZip {
				assert.Error(t, err)
				return
			}
			require.NoError(t, err)
			var firstName string
			require.NoError(t, db.QueryRow(`SELECT first_name FROM clients WHERE client_id = $1`, profile.ClientID.String()).Scan(&firstName))
			assert.Equal(t, profile.FirstName, firstName)
			_, _ = db.Exec(`DELETE FROM clients WHERE client_id = $1`, profile.ClientID.String())
		})
	}
}
