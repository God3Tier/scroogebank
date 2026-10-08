package account

import (
	"context"
	"database/sql"
	"testing"

	accounterrors "account-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestDeleteAccount(t *testing.T) {
	tests := map[string]struct {
		fixture   string
		accountID string
		wantErr   error
	}{
		"soft-deletes an existing account": {
			fixture: "account_lookup.sql", accountID: "00000000-0000-0000-0000-000000000202",
		},
		"returns not found for a missing account": {
			fixture: "account_empty.sql", accountID: "00000000-0000-0000-0000-000000000299", wantErr: accounterrors.ErrNotFound,
		},
		"returns not found for a deleted account": {
			fixture: "account_deleted.sql", accountID: "00000000-0000-0000-0000-000000000206", wantErr: accounterrors.ErrNotFound,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)
			err := NewAccountRepository(db).DeleteAccount(context.Background(), test.accountID)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			// The row is kept, marked deleted, and its status is not changed
			// to closed: deleted and closed are different things.
			var status string
			var deletedAt sql.NullTime
			require.NoError(t, db.QueryRow(`SELECT status, deleted_at FROM accounts WHERE id = $1`, test.accountID).Scan(&status, &deletedAt))
			assert.True(t, deletedAt.Valid)
			assert.Equal(t, "active", status)
		})
	}
}
