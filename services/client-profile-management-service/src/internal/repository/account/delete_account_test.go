package account

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestDeleteAccount(t *testing.T) {
	tests := map[string]struct {
		fixture   string
		accountID string
		wantError bool
	}{
		"deletes an existing account": {
			fixture: "account_lookup.sql", accountID: "00000000-0000-0000-0000-000000000202",
		},
		"returns an error for a missing account": {
			fixture: "account_empty.sql", accountID: "00000000-0000-0000-0000-000000000299", wantError: true,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)
			err := NewAccountRepository(db).DeleteAccount(context.Background(), test.accountID)
			if test.wantError {
				assert.Error(t, err)
				return
			}
			require.NoError(t, err)
			var count int
			require.NoError(t, db.QueryRow(`SELECT COUNT(*) FROM accounts WHERE id = $1`, test.accountID).Scan(&count))
			assert.Zero(t, count)
		})
	}
}
