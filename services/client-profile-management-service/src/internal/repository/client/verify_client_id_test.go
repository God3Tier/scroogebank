package client

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestVerifyClientIdentity(t *testing.T) {
	tests := map[string]struct {
		id      string
		fixture string
		wantErr bool
	}{
		"marks existing client verified":   {id: "00000000-0000-0000-0000-000000000101", fixture: "client_lookup.sql"},
		"returns error for unknown client": {id: "00000000-0000-0000-0000-000000000199", fixture: "client_empty.sql", wantErr: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)
			err := NewClientRepository(db).VerifyClientIdentity(context.Background(), test.id)
			if test.wantErr {
				assert.Error(t, err)
				return
			}
			require.NoError(t, err)
			var status string
			require.NoError(t, db.QueryRow(`SELECT verification_status FROM clients WHERE client_id = $1`, test.id).Scan(&status))
			assert.Equal(t, "verified", status)
		})
	}
}
