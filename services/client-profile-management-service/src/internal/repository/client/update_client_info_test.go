package client

import (
	"context"
	"testing"

	"client-profile-management-service/src/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestUpdateClientInformation(t *testing.T) {
	tests := map[string]struct {
		id      string
		fixture string
		wantErr bool
	}{
		"updates existing profile":          {id: "00000000-0000-0000-0000-000000000101", fixture: "client_lookup.sql"},
		"returns error for unknown profile": {id: "00000000-0000-0000-0000-000000000199", fixture: "client_empty.sql", wantErr: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)
			profile := models.Client{FirstName: "Updated Alice", LastName: "Smith", Email: "updated-101@example.test"}
			err := NewClientRepository(db).UpdateClientInformation(context.Background(), test.id, profile)
			if test.wantErr {
				assert.Error(t, err)
				return
			}
			require.NoError(t, err)
			var firstName, email string
			require.NoError(t, db.QueryRow(`SELECT first_name, email FROM clients WHERE client_id = $1`, test.id).Scan(&firstName, &email))
			assert.Equal(t, profile.FirstName, firstName)
			assert.Equal(t, profile.Email, email)
		})
	}
}
