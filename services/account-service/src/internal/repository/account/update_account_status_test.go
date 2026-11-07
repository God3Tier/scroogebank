package account

import (
	"context"
	"testing"

	accounterrors "account-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestUpdateAccountStatus(t *testing.T) {
	tests := map[string]struct {
		fixture    string
		accountID  string
		status     string
		wantPrev   string
		wantStored string
		wantErr    error
	}{
		"changes the status and returns the old one": {
			fixture: "account_lookup.sql", accountID: "00000000-0000-0000-0000-000000000202", status: "CLOSED",
			wantPrev: "active", wantStored: "closed",
		},
		"accepts the status the account already has": {
			fixture: "account_lookup.sql", accountID: "00000000-0000-0000-0000-000000000202", status: "ACTIVE",
			wantPrev: "active", wantStored: "active",
		},
		"returns not found for a missing account": {
			fixture: "account_empty.sql", accountID: "00000000-0000-0000-0000-000000000299", status: "ACTIVE", wantErr: accounterrors.ErrNotFound,
		},
		"returns not found for a deleted account": {
			fixture: "account_deleted.sql", accountID: "00000000-0000-0000-0000-000000000206", status: "ACTIVE", wantErr: accounterrors.ErrNotFound,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)

			previous, err := NewAccountRepository(db).UpdateAccountStatus(context.Background(), test.accountID, test.status)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, test.wantPrev, previous)
			var status string
			require.NoError(t, db.QueryRow(`SELECT status FROM accounts WHERE id = $1`, test.accountID).Scan(&status))
			assert.Equal(t, test.wantStored, status)
		})
	}
}
